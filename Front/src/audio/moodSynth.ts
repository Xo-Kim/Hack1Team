import type { MusicSpec } from '../types'

/**
 * 무드 스펙으로 앰비언스를 실시간 생성하는 Web Audio 엔진.
 *
 * Spotify Web Playback SDK 를 쓰지 않는 이유:
 * Premium 계정 + OAuth 리다이렉트 + 네트워크가 모두 살아 있어야 소리가 난다.
 * 데모에서 실패 지점을 셋이나 늘릴 값이 없다. 여기서는 자산도 라이선스도
 * 네트워크도 필요 없이 항상 소리가 나고, MusicSpec 을 실제로 소비한다.
 *
 * 교체 시에는 이 클래스의 인터페이스(play/setVolume/stop)만 맞추면 된다.
 */

const NOTE_SEMITONE: Record<string, number> = {
  C: 0, 'C#': 1, D: 2, 'D#': 3, E: 4, F: 5,
  'F#': 6, G: 7, 'G#': 8, A: 9, 'A#': 10, B: 11,
}

/** 아르페지오는 펜타토닉만 쓴다 — 무작위로 골라도 불협이 나지 않는다. */
const MINOR_PENT = [0, 3, 5, 7, 10]
const MAJOR_PENT = [0, 2, 4, 7, 9]

function pentatonicFor(scale: string): number[] {
  return ['major', 'lydian'].includes(scale) ? MAJOR_PENT : MINOR_PENT
}

/** A4 = 440Hz 기준 주파수 계산. */
function freq(note: string, octave: number, semitoneOffset = 0): number {
  const base = NOTE_SEMITONE[note] ?? 2
  const midi = 12 * (octave + 1) + base + semitoneOffset
  return 440 * Math.pow(2, (midi - 69) / 12)
}

/** 노이즈 감쇠로 임펄스 응답을 만들어 리버브에 쓴다. 외부 IR 파일이 필요 없다. */
function makeReverbIR(ctx: AudioContext, seconds: number, decay: number): AudioBuffer {
  const len = Math.floor(ctx.sampleRate * seconds)
  const buf = ctx.createBuffer(2, len, ctx.sampleRate)
  for (let ch = 0; ch < 2; ch++) {
    const data = buf.getChannelData(ch)
    for (let i = 0; i < len; i++) {
      data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, decay)
    }
  }
  return buf
}

function makeNoiseBuffer(ctx: AudioContext): AudioBuffer {
  const len = ctx.sampleRate * 2
  const buf = ctx.createBuffer(1, len, ctx.sampleRate)
  const data = buf.getChannelData(0)
  for (let i = 0; i < len; i++) data[i] = Math.random() * 2 - 1
  return buf
}

export class MoodSynth {
  private ctx: AudioContext | null = null
  private master: GainNode | null = null
  private sources: AudioScheduledSourceNode[] = []
  private arpTimer: number | null = null
  private beatTimer: number | null = null
  private targetVolume = 0.55

  get isPlaying(): boolean {
    return this.ctx !== null
  }

  /**
   * 반드시 사용자 제스처(클릭) 안에서 호출해야 한다.
   * 브라우저는 제스처 없이 만든 AudioContext 를 suspended 로 둔다.
   */
  async play(spec: MusicSpec, fadeMs: number): Promise<void> {
    this.stop(0)

    const ctx = new AudioContext()
    if (ctx.state === 'suspended') await ctx.resume()
    this.ctx = ctx

    const master = ctx.createGain()
    master.gain.value = 0.0001
    master.connect(ctx.destination)
    this.master = master

    const reverb = ctx.createConvolver()
    reverb.buffer = makeReverbIR(ctx, 2.8, 2.4)
    const wet = ctx.createGain()
    wet.gain.value = 0.5
    reverb.connect(wet).connect(master)

    const energy = clamp01(spec.energy)
    const pent = pentatonicFor(spec.scale)

    this.buildDrone(ctx, master, reverb, spec, energy)
    this.buildAirLayer(ctx, master, energy)
    this.startArpeggio(ctx, reverb, master, spec, pent, energy)
    this.startBeat(ctx, master, reverb, spec, energy)

    // 조명 전환과 같은 속도로 페이드인 — 빛과 소리가 같이 들어와야 한 장면이 된다.
    const now = ctx.currentTime
    master.gain.setValueAtTime(0.0001, now)
    master.gain.exponentialRampToValueAtTime(this.targetVolume, now + Math.max(0.1, fadeMs / 1000))
  }

  /** 근음 + 5도 + 옥타브 드론. 이 소리가 씬의 바닥을 만든다. */
  private buildDrone(
    ctx: AudioContext,
    master: GainNode,
    reverb: ConvolverNode,
    spec: MusicSpec,
    energy: number,
  ): void {
    const filter = ctx.createBiquadFilter()
    filter.type = 'lowpass'
    filter.frequency.value = 340 + energy * 1500
    filter.Q.value = 0.7

    const gain = ctx.createGain()
    gain.gain.value = 0.09

    filter.connect(gain)
    gain.connect(master)
    gain.connect(reverb)

    // 근음(옥타브 2), 5도, 옥타브 위 — 셋 다 살짝 디튠해서 두께를 만든다.
    const voices: Array<[number, number, number]> = [
      [freq(spec.key, 2), -6, 0.9],
      [freq(spec.key, 2, 7), +5, 0.55],
      [freq(spec.key, 3), +9, 0.35],
    ]

    for (const [f, detune, level] of voices) {
      const osc = ctx.createOscillator()
      osc.type = 'sawtooth'
      osc.frequency.value = f
      osc.detune.value = detune

      const vg = ctx.createGain()
      vg.gain.value = level
      osc.connect(vg).connect(filter)
      osc.start()
      this.sources.push(osc)
    }

    // 아주 느린 필터 스윕. 소리가 "숨쉬게" 만들어 정적인 드론 느낌을 없앤다.
    const lfo = ctx.createOscillator()
    lfo.type = 'sine'
    lfo.frequency.value = 0.06
    const lfoGain = ctx.createGain()
    lfoGain.gain.value = 220 + energy * 400
    lfo.connect(lfoGain).connect(filter.frequency)
    lfo.start()
    this.sources.push(lfo)
  }

  /** 대역 제한 노이즈. 공간감을 주는 얇은 공기층. */
  private buildAirLayer(ctx: AudioContext, master: GainNode, energy: number): void {
    const noise = ctx.createBufferSource()
    noise.buffer = makeNoiseBuffer(ctx)
    noise.loop = true

    const bp = ctx.createBiquadFilter()
    bp.type = 'bandpass'
    bp.frequency.value = 900 + energy * 1400
    bp.Q.value = 0.8

    const gain = ctx.createGain()
    gain.gain.value = 0.014

    noise.connect(bp).connect(gain).connect(master)
    noise.start()
    this.sources.push(noise)
  }

  /** bpm 에 맞춰 펜타토닉 음을 띄엄띄엄 흩뿌린다. energy 가 높을수록 자주 울린다. */
  private startArpeggio(
    ctx: AudioContext,
    reverb: ConvolverNode,
    master: GainNode,
    spec: MusicSpec,
    pent: number[],
    energy: number,
  ): void {
    const intervalMs = (60_000 / Math.max(40, spec.bpm)) * 2
    const density = 0.25 + energy * 0.45

    this.arpTimer = window.setInterval(() => {
      if (!this.ctx || Math.random() > density) return

      const octave = Math.random() < 0.6 ? 4 : 5
      const semitone = pent[Math.floor(Math.random() * pent.length)]
      const f = freq(spec.key, octave, semitone)

      const osc = ctx.createOscillator()
      osc.type = 'triangle'
      osc.frequency.value = f

      const g = ctx.createGain()
      const t = ctx.currentTime
      const peak = 0.05 + energy * 0.04
      g.gain.setValueAtTime(0.0001, t)
      g.gain.exponentialRampToValueAtTime(peak, t + 0.02)
      g.gain.exponentialRampToValueAtTime(0.0001, t + 1.6)

      osc.connect(g)
      g.connect(master)
      g.connect(reverb)

      osc.start(t)
      osc.stop(t + 1.7)
    }, intervalMs)
  }

  /* ------------------------------------------------------------------ 비트
   *
   * MCM 의 음악적 뿌리는 힙합·스트릿이다 (PRD §3.2). 드론만 깔면 스파 음악처럼
   * 들려 브랜드와 정반대가 되므로, 리듬을 얹어 방향을 잡는다.
   *
   * setInterval 로 드럼을 직접 치면 타이밍이 흔들려 바로 티가 난다.
   * 그래서 짧은 주기로 깨어나 ctx.currentTime 기준 앞당겨 예약하는
   * lookahead 스케줄링을 쓴다.
   */

  private static readonly KICK_STEPS = [0, 10]
  private static readonly KICK_STEPS_BUSY = [0, 6, 10]
  private static readonly SNARE_STEPS = [4, 12]

  private startBeat(
    ctx: AudioContext,
    master: GainNode,
    reverb: ConvolverNode,
    spec: MusicSpec,
    energy: number,
  ): void {
    const bpm = Math.max(60, Math.min(120, spec.bpm))
    const stepDur = 60 / bpm / 4          // 16분음표 한 칸
    const lookahead = 0.18                // 이만큼 앞당겨 예약해 둔다

    const kicks = energy > 0.55 ? MoodSynth.KICK_STEPS_BUSY : MoodSynth.KICK_STEPS
    const rootHz = freq(spec.key, 1)      // 킥과 함께 깔리는 서브 베이스

    let nextTime = ctx.currentTime + 0.12
    let step = 0

    this.beatTimer = window.setInterval(() => {
      if (!this.ctx) return
      while (nextTime < ctx.currentTime + lookahead) {
        if (kicks.includes(step)) {
          this.kick(ctx, master, nextTime, energy)
          this.subBass(ctx, master, nextTime, rootHz, stepDur * 4)
        }
        if (MoodSynth.SNARE_STEPS.includes(step)) {
          this.snare(ctx, master, reverb, nextTime, energy)
        }
        if (step % 2 === 0) {
          // 오프비트를 살짝 세게 쳐서 기계적으로 들리지 않게 한다
          this.hat(ctx, master, nextTime, step % 4 === 2 ? 0.9 : 0.55, energy)
        }
        nextTime += stepDur
        step = (step + 1) % 16
      }
    }, 25)
  }

  /** 사인파의 피치를 떨어뜨려 만드는 킥. */
  private kick(ctx: AudioContext, master: GainNode, t: number, energy: number): void {
    const osc = ctx.createOscillator()
    osc.type = 'sine'
    osc.frequency.setValueAtTime(150, t)
    osc.frequency.exponentialRampToValueAtTime(48, t + 0.11)

    const g = ctx.createGain()
    g.gain.setValueAtTime(0.0001, t)
    g.gain.exponentialRampToValueAtTime(0.22 + energy * 0.1, t + 0.005)
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.24)

    osc.connect(g).connect(master)
    osc.start(t)
    osc.stop(t + 0.28)
  }

  private subBass(ctx: AudioContext, master: GainNode, t: number, hz: number, dur: number): void {
    const osc = ctx.createOscillator()
    osc.type = 'sine'
    osc.frequency.value = hz

    const g = ctx.createGain()
    g.gain.setValueAtTime(0.0001, t)
    g.gain.exponentialRampToValueAtTime(0.1, t + 0.03)
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur)

    osc.connect(g).connect(master)
    osc.start(t)
    osc.stop(t + dur + 0.05)
  }

  /** 노이즈 버스트 + 하이패스. 리버브를 조금 태워 공간에 앉힌다. */
  private snare(
    ctx: AudioContext,
    master: GainNode,
    reverb: ConvolverNode,
    t: number,
    energy: number,
  ): void {
    const noise = ctx.createBufferSource()
    noise.buffer = makeNoiseBuffer(ctx)

    const hp = ctx.createBiquadFilter()
    hp.type = 'highpass'
    hp.frequency.value = 1400

    const g = ctx.createGain()
    g.gain.setValueAtTime(0.0001, t)
    g.gain.exponentialRampToValueAtTime(0.13 + energy * 0.06, t + 0.004)
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.16)

    noise.connect(hp).connect(g)
    g.connect(master)
    g.connect(reverb)
    noise.start(t)
    noise.stop(t + 0.2)
  }

  private hat(
    ctx: AudioContext,
    master: GainNode,
    t: number,
    velocity: number,
    energy: number,
  ): void {
    const noise = ctx.createBufferSource()
    noise.buffer = makeNoiseBuffer(ctx)

    const hp = ctx.createBiquadFilter()
    hp.type = 'highpass'
    hp.frequency.value = 7500

    const g = ctx.createGain()
    const peak = (0.035 + energy * 0.025) * velocity
    g.gain.setValueAtTime(0.0001, t)
    g.gain.exponentialRampToValueAtTime(peak, t + 0.002)
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.05)

    noise.connect(hp).connect(g).connect(master)
    noise.start(t)
    noise.stop(t + 0.07)
  }

  /** 직원 응대 모드에서 볼륨을 낮춘다 — 음악 위로 대화가 가능해야 한다. (PRD §8.1) */
  setVolume(volume: number, rampMs = 400): void {
    this.targetVolume = clamp01(volume)
    if (!this.ctx || !this.master) return
    const now = this.ctx.currentTime
    this.master.gain.cancelScheduledValues(now)
    this.master.gain.setValueAtTime(Math.max(0.0001, this.master.gain.value), now)
    this.master.gain.exponentialRampToValueAtTime(
      Math.max(0.0001, this.targetVolume),
      now + rampMs / 1000,
    )
  }

  stop(fadeMs = 900): void {
    const ctx = this.ctx
    const master = this.master
    if (!ctx || !master) {
      this.cleanup()
      return
    }

    this.clearTimers()

    const now = ctx.currentTime
    master.gain.cancelScheduledValues(now)
    master.gain.setValueAtTime(Math.max(0.0001, master.gain.value), now)
    master.gain.exponentialRampToValueAtTime(0.0001, now + Math.max(0.05, fadeMs / 1000))

    const sources = this.sources
    window.setTimeout(() => {
      for (const s of sources) {
        try {
          s.stop()
        } catch {
          /* 이미 정지된 노드 */
        }
      }
      void ctx.close()
    }, fadeMs + 120)

    this.ctx = null
    this.master = null
    this.sources = []
  }

  private cleanup(): void {
    this.clearTimers()
    this.sources = []
    this.ctx = null
    this.master = null
  }

  /** 아르페지오와 비트 스케줄러를 함께 끈다. 하나라도 남으면 소리가 계속 난다. */
  private clearTimers(): void {
    if (this.arpTimer !== null) {
      window.clearInterval(this.arpTimer)
      this.arpTimer = null
    }
    if (this.beatTimer !== null) {
      window.clearInterval(this.beatTimer)
      this.beatTimer = null
    }
  }
}

function clamp01(v: number): number {
  return Math.max(0, Math.min(1, v))
}
