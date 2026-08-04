import type { MusicSpec, MusicTrack } from '../types'
import { MoodSynth } from './moodSynth'

export type AudioMode = 'track' | 'synth' | 'none'

/**
 * 음향 재생 단일 창구.
 *
 * 실제 음원(Jamendo)이 있으면 <audio> 로 재생하고, 없거나 실패하면
 * 절차적 앰비언스로 폴백한다. App 은 어느 쪽인지 몰라도 된다.
 *
 * 소리가 아예 안 나는 상태는 만들지 않는다는 것이 이 클래스의 계약이다. (PRD §18)
 */
export class MoodAudio {
  private synth = new MoodSynth()
  private el: HTMLAudioElement | null = null
  private fadeTimer: number | null = null

  private _mode: AudioMode = 'none'
  private volume = 0.55
  private muted = false

  get mode(): AudioMode {
    return this._mode
  }

  /** 반드시 사용자 제스처 안에서 호출해야 한다 (AudioContext / audio.play() 둘 다). */
  async play(spec: MusicSpec, track: MusicTrack | null, fadeMs: number): Promise<void> {
    this.stop(0)

    if (track?.audioUrl) {
      const ok = await this.playTrack(track, fadeMs)
      if (ok) {
        this._mode = 'track'
        return
      }
      // 음원 재생이 막히면 조용히 앰비언스로 넘어간다. 데모가 무음이 되는 것보다 낫다.
      console.warn('[audio] 음원 재생 실패 — 절차적 앰비언스로 폴백')
    }

    // 신스까지 실패할 수 있다 (오디오 장치 없음, AudioContext 생성 거부 등).
    // 이때 조용히 넘어가면 "소리도 안 나고 이유도 모르는" 상태가 되므로
    // mode 를 'none' 으로 남겨 UI 가 재시도를 제안하게 한다.
    try {
      await this.synth.play(spec, fadeMs)
      this.synth.setVolume(this.effectiveVolume(), 200)
      this._mode = 'synth'
    } catch (e) {
      console.warn('[audio] 절차적 앰비언스도 실패 — 무음 상태', e)
      this._mode = 'none'
    }
  }

  private async playTrack(track: MusicTrack, fadeMs: number): Promise<boolean> {
    const el = new Audio()
    el.src = track.audioUrl
    el.crossOrigin = 'anonymous'
    // 3분 타임아웃보다 짧은 곡이 걸릴 수 있어 반복 재생한다. 세션 중간에
    // 소리가 끊기면 연출이 끝난 것처럼 보인다.
    el.loop = true
    el.preload = 'auto'
    el.volume = 0

    this.el = el

    try {
      await el.play()
    } catch {
      this.el = null
      return false
    }

    this.fadeTo(this.effectiveVolume(), fadeMs)
    return true
  }

  /** HTMLAudioElement 는 볼륨 램프가 없어서 직접 보간한다. */
  private fadeTo(target: number, ms: number): void {
    const el = this.el
    if (!el) return

    if (this.fadeTimer !== null) {
      window.clearInterval(this.fadeTimer)
      this.fadeTimer = null
    }

    const steps = Math.max(1, Math.round(ms / 40))
    const from = el.volume
    let i = 0

    this.fadeTimer = window.setInterval(() => {
      i += 1
      const t = Math.min(1, i / steps)
      el.volume = Math.max(0, Math.min(1, from + (target - from) * t))
      if (t >= 1 && this.fadeTimer !== null) {
        window.clearInterval(this.fadeTimer)
        this.fadeTimer = null
      }
    }, 40)
  }

  private effectiveVolume(): number {
    return this.muted ? 0 : this.volume
  }

  /** 직원 응대 모드 ducking. (PRD §8.1 / F-11) */
  setVolume(volume: number, rampMs = 400): void {
    this.volume = Math.max(0, Math.min(1, volume))
    this.applyVolume(rampMs)
  }

  setMuted(muted: boolean): void {
    this.muted = muted
    this.applyVolume(250)
  }

  get isMuted(): boolean {
    return this.muted
  }

  private applyVolume(rampMs: number): void {
    if (this._mode === 'track') {
      this.fadeTo(this.effectiveVolume(), rampMs)
    } else if (this._mode === 'synth') {
      // 신스는 exponentialRamp 라 0 이 될 수 없다. 들리지 않는 값으로 내린다.
      this.synth.setVolume(Math.max(0.0001, this.effectiveVolume()), rampMs)
    }
  }

  stop(fadeMs = 900): void {
    this.synth.stop(fadeMs)

    if (this.fadeTimer !== null) {
      window.clearInterval(this.fadeTimer)
      this.fadeTimer = null
    }

    const el = this.el
    if (el) {
      this.el = null
      this.fadeTimerFreeStop(el, fadeMs)
    }

    this._mode = 'none'
  }

  /** 페이드아웃 후 확실히 정지시킨다. src 를 비워야 백그라운드 버퍼링도 멈춘다. */
  private fadeTimerFreeStop(el: HTMLAudioElement, fadeMs: number): void {
    const steps = Math.max(1, Math.round(fadeMs / 40))
    const from = el.volume
    let i = 0

    const kill = () => {
      el.pause()
      el.removeAttribute('src')
      el.load()
    }

    if (fadeMs <= 0) {
      kill()
      return
    }

    const timer = window.setInterval(() => {
      i += 1
      el.volume = Math.max(0, from * (1 - i / steps))
      if (i >= steps) {
        window.clearInterval(timer)
        kill()
      }
    }, 40)
  }
}
