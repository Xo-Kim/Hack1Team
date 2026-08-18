import type { MusicSpec, MusicTrack } from '../types'
import { MoodSynth } from './moodSynth'

/**
 * 재생 상태.
 * - track   실제 음원 재생 중
 * - synth   절차적 앰비언스
 * - blocked 음원은 준비됐으나 자동재생이 막힘 — 탭 한 번이면 재생된다
 * - none    아무것도 못 냄
 */
export type AudioMode = 'track' | 'synth' | 'blocked' | 'none'

/** playTrack 결과. 자동재생 거부와 진짜 실패는 다르게 다뤄야 한다. */
type TrackResult = 'ok' | 'blocked' | 'failed'

/**
 * 음향 재생 단일 창구.
 *
 * 실제 음원(Jamendo)이 있으면 <audio> 로 재생하고, 없거나 실패하면
 * 절차적 앰비언스로 폴백한다. App 은 어느 쪽인지 몰라도 된다.
 *
 * 소리가 아예 안 나는 상태는 만들지 않는다는 것이 이 클래스의 계약이다. (PRD §18)
 */
/**
 * 살아 있는 인스턴스. HMR 정리에만 쓴다.
 * <p>
 * 개발 중 이 모듈이 교체되면 React 가 컴포넌트를 다시 마운트하면서 새 MoodAudio 를
 * 만드는데, 이전 인스턴스의 AudioContext 는 계속 울린다. 참조가 사라졌으니 음소거도
 * 정지도 먹지 않는다 — "끄기를 눌러도 소리가 계속 나는" 상태가 이렇게 만들어진다.
 * 모듈이 버려질 때 전부 정지시킨다.
 */
const liveInstances = new Set<MoodAudio>()

if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    for (const instance of liveInstances) {
      instance.stop(0)
    }
    liveInstances.clear()
  })
}

export class MoodAudio {
  private synth = new MoodSynth()
  private el: HTMLAudioElement | null = null
  private fadeTimer: number | null = null

  private _mode: AudioMode = 'none'
  private volume = 0.55
  private muted = false

  constructor() {
    liveInstances.add(this)
  }

  get mode(): AudioMode {
    return this._mode
  }

  /** 반드시 사용자 제스처 안에서 호출해야 한다 (AudioContext / audio.play() 둘 다). */
  async play(spec: MusicSpec, track: MusicTrack | null, fadeMs: number): Promise<void> {
    this.stop(0)

    this.pending = { spec, track, fadeMs }

    if (track?.audioUrl) {
      const result = await this.playTrack(track, fadeMs)
      if (result === 'ok') {
        this._mode = 'track'
        return
      }
      /*
       * 자동재생 거부는 실패가 아니다.
       *
       * 여기서 신스로 넘어가면 실제 음원이 멀쩡한데도 킥·스네어만 나오는 상태가
       * 되고, 브라우저 정책은 상황에 따라 허용되기도 해서 "어떤 때는 음악이,
       * 어떤 때는 비트만" 나오는 것처럼 보인다. 음원을 버리지 말고 재생 버튼을
       * 띄워 탭 한 번으로 살린다.
       */
      if (result === 'blocked') {
        console.warn('[audio] 자동재생 차단 — 사용자 조작 대기')
        this._mode = 'blocked'
        return
      }
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

  /** play() 인자를 보관한다. 자동재생이 풀렸을 때 같은 조건으로 다시 걸기 위해서다. */
  private pending: { spec: MusicSpec; track: MusicTrack | null; fadeMs: number } | null = null

  /** 자동재생이 막혔을 때 UI 의 재생 버튼이 부른다. 반드시 클릭 핸들러 안에서. */
  async resumeFromGesture(): Promise<void> {
    const p = this.pending
    if (!p) return
    await this.play(p.spec, p.track, p.fadeMs)
  }

  private async playTrack(track: MusicTrack, fadeMs: number): Promise<TrackResult> {
    const el = new Audio()

    /*
     * crossOrigin 을 설정하지 않는다.
     *
     * 'anonymous' 를 걸면 브라우저가 CORS 모드로 요청하고, 응답에
     * Access-Control-Allow-Origin 이 없으면 리소스를 통째로 버린다.
     * Jamendo 저장소(prod-N.storage.jamendo.com)는 오디오에 CORS 헤더를
     * 주지 않으므로 — 실측 확인 — 이 속성이 있으면 음원이 100% 실패하고
     * 절차적 앰비언스로만 재생된다. 실제로 그 상태였다.
     *
     * <audio> 로 그냥 재생하는 데는 CORS 가 필요 없다. 필요해지는 건
     * Web Audio 로 이 엘리먼트의 파형을 분석할 때인데, 우리는 하지 않는다.
     */
    el.src = track.audioUrl
    // 3분 타임아웃보다 짧은 곡이 걸릴 수 있어 반복 재생한다. 세션 중간에
    // 소리가 끊기면 연출이 끝난 것처럼 보인다.
    el.loop = true
    el.preload = 'auto'
    el.volume = 0
    el.muted = this.muted

    this.el = el

    try {
      await el.play()
    } catch (e) {
      // NotAllowedError = 자동재생 정책. 그 외는 네트워크·코덱 문제다.
      if (e instanceof DOMException && e.name === 'NotAllowedError') {
        // 엘리먼트를 살려 둔다. 사용자가 탭하면 play() 만 다시 부르면 된다.
        return 'blocked'
      }
      console.warn('[audio] 음원 재생 실패:', e)
      el.removeAttribute('src')
      this.el = null
      return 'failed'
    }

    this.fadeTo(this.effectiveVolume(), fadeMs)
    return 'ok'
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

  /**
   * 음소거.
   * <p>
   * <b>페이드로 처리하지 않는다.</b> 볼륨 램프는 진행 중인 다른 램프와 경쟁하고,
   * 신스는 exponentialRamp 라 정확히 0 에 닿지도 못한다. 사용자가 끄기를 눌렀으면
   * 즉시 조용해져야 하므로 엘리먼트는 네이티브 muted 속성을 쓴다.
   */
  setMuted(muted: boolean): void {
    this.muted = muted
    if (this.el) {
      this.el.muted = muted
    }
    this.applyVolume(250)
  }

  get isMuted(): boolean {
    return this.muted
  }

  /**
   * 볼륨을 양쪽 재생 경로에 모두 적용한다.
   * <p>
   * 예전에는 {@code _mode} 로 갈라 한쪽에만 적용했는데, 폴백 도중이거나 모드가
   * 아직 확정되지 않은 순간에 호출되면 조작이 통째로 무시됐다. 어느 쪽이 울고
   * 있든 끄라는 지시는 지켜져야 하므로 분기하지 않는다.
   */
  private applyVolume(rampMs: number): void {
    const target = this.effectiveVolume()

    if (this.el) {
      this.fadeTo(target, rampMs)
    }
    if (this.synth.isPlaying) {
      // 신스는 exponentialRamp 라 0 이 될 수 없다. 들리지 않는 값으로 내린다.
      this.synth.setVolume(Math.max(0.0001, target), rampMs)
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
