import { useCallback, useEffect, useRef, useState } from 'react'
import { HttpError, health, isSessionGone, mirror } from '../api'
import { MoodAudio, type AudioMode } from '../audio/moodAudio'
import { useCamera } from '../hooks/useCamera'
import { useIdleTimeout } from '../hooks/useIdleTimeout'
import { effectClass, lightingToCssVars } from '../lighting'
import type { HealthResponse, MoodAnalysis, MusicTrack, SessionState } from '../types'
import {
  AnalyzingScreen,
  ChoiceScreen,
  ConsentScreen,
  CountdownScreen,
  ErrorScreen,
  IdleScreen,
  MoodScreen,
  PermissionScreen,
  WaitingForStaffScreen,
} from './screens'

/**
 * 화면 단계. 서버 세션 상태와 1:1 은 아니다.
 * <p>
 * 동의 화면·카운트다운은 서버가 관측할 수 없는 클라이언트 단계이고, 반대로
 * ASSIST_ACCEPTED 는 화면상 '직원 대기'와 같은 단계로 묶인다. 서버 상태를 그대로
 * 화면 단계로 쓰면 이 둘을 표현할 수 없다.
 */
type Phase =
  | 'idle'
  | 'consent'
  | 'permission'
  | 'countdown'
  | 'analyzing'
  | 'mood'
  | 'choice'
  | 'assist'
  | 'selfBrowsing'
  | 'error'

const COUNTDOWN_FROM = 3
const SERVING_VOLUME = 0.18
const NORMAL_VOLUME = 0.55

/** 무입력 3분이면 대기 화면으로 돌아간다. 서버 세션도 함께 정리한다. */
const IDLE_TIMEOUT_MS = 3 * 60 * 1000

/** 응대 상태 변화를 감지하는 주기. 직원이 오면 음악을 줄여야 한다. */
const POLL_MS = 3000

const MIRROR_ID = import.meta.env.VITE_MIRROR_ID ?? 'mirror-01'
const STORE_ID = import.meta.env.VITE_STORE_ID ?? 'mcm-seoul'
const MIRROR_LABEL = import.meta.env.VITE_MIRROR_LABEL ?? '2F 피팅룸 A'

export function MirrorApp() {
  const { videoRef, state: cameraState, error: cameraError, capture, retry } = useCamera()

  const [phase, setPhase] = useState<Phase>('idle')
  const [count, setCount] = useState(COUNTDOWN_FROM)
  const [step, setStep] = useState(0)

  const [sessionId, setSessionId] = useState<string | null>(null)
  const [analysis, setAnalysis] = useState<MoodAnalysis | null>(null)
  const [track, setTrack] = useState<MusicTrack | null>(null)

  const [audioMode, setAudioMode] = useState<AudioMode>('none')
  const [muted, setMuted] = useState(false)
  /** 직원이 응대를 잡았는지. 이름은 받지 않는다 — 고객에게 필요한 건 "곧 온다"뿐이다. */
  const [serving, setServing] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [health_, setHealth] = useState<HealthResponse | null>(null)

  const audio = useRef<MoodAudio | null>(null)
  if (audio.current === null) {
    audio.current = new MoodAudio()
  }

  useEffect(() => {
    health().then(setHealth).catch(() => setHealth(null))
  }, [])

  // 모드는 엔진이 알려준다. play() 가 끝난 뒤 한 번 읽는 방식이면 로딩(3~4초)과
  // 폴백 전환이 화면에 반영되지 않아, 음원이 오는 중인데도 재생 버튼이 떠 있었다.
  useEffect(() => {
    const engine = audio.current
    engine?.observe(setAudioMode)
    return () => engine?.stop(0)
  }, [])

  // ------------------------------------------------------------------ 리셋

  const resetLocal = useCallback(() => {
    audio.current?.stop()
    setPhase('idle')
    setCount(COUNTDOWN_FROM)
    setStep(0)
    setSessionId(null)
    setAnalysis(null)
    setTrack(null)
    setAudioMode('none')
    setMuted(false)
    setServing(false)
    setErrorMessage(null)
  }, [])

  /**
   * 서버 세션까지 정리한다.
   * <p>
   * 화면만 초기화하면 서버 세션이 남아 <b>직원 대기 목록에 유령 요청이 계속 떠 있다.</b>
   * 직원이 아무도 없는 미러로 찾아가게 되므로 반드시 서버에도 알린다.
   */
  const resetAll = useCallback(() => {
    const id = sessionId
    resetLocal()
    if (id) {
      mirror.reset(id).catch(() => {
        /* 이미 만료됐을 수 있다. 화면은 어차피 대기로 돌아갔으므로 무시한다. */
      })
    }
  }, [sessionId, resetLocal])

  /**
   * 고객이 스스로 마친다. 완주로 기록된다.
   * <p>
   * resetAll(=EXPIRED) 과 갈라 둔다. 직원이 응대를 마쳐도 여기까지 오지 않는다 —
   * 화면을 끄는 것은 고객의 몫이다.
   */
  const endSession = useCallback(() => {
    const id = sessionId
    resetLocal()
    if (id) {
      mirror.end(id).catch(() => {
        /* 이미 만료됐을 수 있다. 화면은 대기로 돌아갔으므로 무시한다. */
      })
    }
  }, [sessionId, resetLocal])

  useIdleTimeout(phase !== 'idle', IDLE_TIMEOUT_MS, resetAll)

  // -------------------------------------------------------------- 세션 시작

  const beginSession = useCallback(async () => {
    setErrorMessage(null)
    try {
      const res = await mirror.start(MIRROR_ID, STORE_ID, MIRROR_LABEL)
      setSessionId(res.sessionId)
      setPhase('consent')
    } catch (e) {
      setErrorMessage(e instanceof Error ? e.message : '세션을 시작하지 못했습니다.')
      setPhase('error')
    }
  }, [])

  // ---------------------------------------------------------------- 분석
  //
  // 선언 순서가 호출 순서의 역순이다 (분석 → 카운트다운 → 동의).
  // 뒤에 선언된 콜백을 앞에서 참조하면 그 시점의 오래된 클로저를 잡을 수 있어서,
  // 의존 관계가 아래에서 위로 흐르도록 배치했다.

  const runAnalysis = useCallback(async () => {
    if (!sessionId) return

    const dataUrl = capture()
    if (!dataUrl) {
      setErrorMessage('카메라 프레임을 읽지 못했습니다. 다시 시도해 주세요.')
      setPhase('error')
      return
    }

    setPhase('analyzing')
    setStep(0)
    const stepTimer = window.setInterval(() => setStep((s) => Math.min(s + 1, 2)), 1200)

    try {
      const res = await mirror.analyze(sessionId, dataUrl)
      window.clearInterval(stepTimer)

      // 폴백 여부는 고객 화면에 드러내지 않는다. 카피 보이스 §8 —
      // 폴백 상황에서도 변명하지 않는다. 확인이 필요하면 직원 카드의
      // analysisFallback 또는 서버 로그를 본다.
      setAnalysis(res.analysis)
      setTrack(res.track)
      setPhase('mood')

      // 빛과 소리를 같은 시간에 걸쳐 올린다. 모드는 engine.observe 가 갱신한다.
      const engine = audio.current
      if (engine) {
        engine.setVolume(NORMAL_VOLUME, 0)
        await engine.play(res.analysis.music, res.track, res.analysis.lighting.transitionMs)
      }
    } catch (e) {
      window.clearInterval(stepTimer)
      if (isSessionGone(e)) {
        resetLocal()
        return
      }
      // 깨진 이미지(400)면 세션은 CONSENTED 로 살아 있다. 그대로 다시 찍으면 된다.
      const retryable = e instanceof HttpError && e.status === 400
      setErrorMessage(
        (e instanceof Error ? e.message : '분석에 실패했습니다.') +
          (retryable ? '' : ' 처음부터 다시 시작해 주세요.'),
      )
      setPhase('error')
    }
  }, [sessionId, capture, resetLocal])

  const startCountdown = useCallback(() => {
    setPhase('countdown')
    setCount(COUNTDOWN_FROM)

    let n = COUNTDOWN_FROM
    const tick = window.setInterval(() => {
      n -= 1
      if (n > 0) {
        setCount(n)
        return
      }
      window.clearInterval(tick)
      void runAnalysis()
    }, 1000)
  }, [runAnalysis])

  /**
   * 동의 → 촬영.
   * <p>
   * 카운트다운을 클릭 핸들러가 시작한 체인 안에서 돌리는 것이 중요하다.
   * AudioContext 는 사용자 제스처 없이는 살아나지 않아서, 여기서 체인을 끊으면
   * 연출이 시작돼도 소리가 나지 않는다.
   */
  const acceptConsent = useCallback(async () => {
    if (!sessionId) return
    try {
      await mirror.consent(sessionId)
    } catch (e) {
      if (isSessionGone(e)) {
        resetLocal()
        return
      }
      setErrorMessage(e instanceof Error ? e.message : '동의 처리에 실패했습니다.')
      setPhase('error')
      return
    }

    if (cameraState !== 'ready') {
      setPhase('permission')
      return
    }
    startCountdown()
  }, [sessionId, cameraState, resetLocal, startCountdown])

  // ------------------------------------------------------------- 응대 선택

  const chooseAssist = useCallback(async () => {
    if (!sessionId) return
    try {
      await mirror.requestAssist(sessionId)
      setPhase('assist')
    } catch (e) {
      if (isSessionGone(e)) resetLocal()
    }
  }, [sessionId, resetLocal])

  const chooseSelfBrowse = useCallback(async () => {
    if (!sessionId) return
    try {
      await mirror.selfBrowse(sessionId)
      setPhase('selfBrowsing')
    } catch (e) {
      if (isSessionGone(e)) resetLocal()
    }
  }, [sessionId, resetLocal])

  const cancelAssist = useCallback(async () => {
    if (!sessionId) return
    try {
      await mirror.cancelAssist(sessionId)
      setPhase('mood')
      setServing(false)
      audio.current?.setVolume(NORMAL_VOLUME, 700)
    } catch (e) {
      if (isSessionGone(e)) resetLocal()
      // 이미 직원이 응대를 시작했으면 409 다. 화면은 그대로 두는 게 맞다.
    }
  }, [sessionId, resetLocal])

  // ------------------------------------------------ 서버 상태 폴링 (응대 감지)

  /**
   * 서버가 알려준 상태를 화면에 반영한다.
   * <p>
   * `musicDucked` 를 상태값으로 추론하지 않고 서버가 준 그대로 쓴다. 볼륨을 낮출지는
   * 연출 정책이지 클라이언트가 판단할 일이 아니다.
   */
  const applyServerState = useCallback(
    (state: SessionState, ducked: boolean) => {
      setServing(state === 'ASSIST_ACCEPTED')
      audio.current?.setVolume(ducked ? SERVING_VOLUME : NORMAL_VOLUME, 700)

      if (state === 'EXPIRED' || state === 'ENDED') {
        resetLocal()
        return
      }
      // 직원이 응대를 마치면 MOOD_ACTIVE 로 돌아온다. 화면을 끄지 않고 연출을 잇는다.
      if (state === 'MOOD_ACTIVE') setPhase((p) => (p === 'assist' ? 'mood' : p))
      if (state === 'ASSIST_REQUESTED' || state === 'ASSIST_ACCEPTED') setPhase('assist')
      if (state === 'SELF_BROWSING') setPhase('selfBrowsing')
    },
    [resetLocal],
  )

  useEffect(() => {
    const watching = phase === 'assist' || phase === 'selfBrowsing' || phase === 'mood'
    if (!sessionId || !watching) return

    const timer = window.setInterval(async () => {
      try {
        const s = await mirror.state(sessionId)
        applyServerState(s.state, s.musicDucked)
      } catch (e) {
        if (isSessionGone(e)) resetLocal()
      }
    }, POLL_MS)

    return () => window.clearInterval(timer)
  }, [sessionId, phase, resetLocal, applyServerState])

  // ------------------------------------------------------------------ 오디오

  const toggleMute = useCallback(() => {
    setMuted((on) => {
      const next = !on
      audio.current?.setMuted(next)
      return next
    })
  }, [])

  /**
   * 자동재생이 막혔을 때의 재생 버튼.
   * <p>
   * 클릭 핸들러 안에서 불려야 브라우저가 허용한다. 마지막 play() 인자를 엔진이
   * 들고 있으므로 같은 곡·같은 페이드로 다시 건다 — 신스로 대체하지 않는다.
   */
  const retryAudio = useCallback(async () => {
    const engine = audio.current
    if (!engine) return
    await engine.resumeFromGesture()
    engine.setMuted(muted)
  }, [muted])

  // ------------------------------------------------------------------ 렌더

  const lit = phase === 'mood' || phase === 'choice' || phase === 'assist' || phase === 'selfBrowsing'
  const lighting = lit && analysis ? analysis.lighting : null

  return (
    <div className="app" style={lightingToCssVars(lighting)}>
      <main className={`stage ${effectClass(lighting)} ${serving ? 'is-serving' : ''}`}>
        <video ref={videoRef} className="mirror" autoPlay playsInline muted />

        <div className="light light--primary" />
        <div className="light light--accent" />
        <div className="light light--sweep" />
        <div className="vignette" />
        <div className="grain" />

        <div className="statusbar">
          <span className="statusbar__dot" data-mode={health_?.llmMode ?? 'offline'} />
          <span>
            {health_
              ? `${health_.llmMode === 'live' ? 'AI 연결됨' : 'AI mock (키 미설정)'}` +
                ` · ${health_.musicMode === 'jamendo' ? '음원 Jamendo' : '생성 앰비언스'}`
              : '백엔드 연결 안 됨'}
          </span>
          {sessionId && <span className="statusbar__session">{MIRROR_LABEL}</span>}
        </div>

        {phase === 'idle' && <IdleScreen onStart={beginSession} />}

        {phase === 'consent' && (
          <ConsentScreen onAccept={acceptConsent} onCancel={resetAll} />
        )}

        {phase === 'permission' && (
          <PermissionScreen
            cameraState={cameraState}
            error={cameraError}
            onRetry={retry}
            onContinue={startCountdown}
            onCancel={resetAll}
          />
        )}

        {phase === 'countdown' && <CountdownScreen value={count} />}

        {phase === 'analyzing' && <AnalyzingScreen step={step} />}

        {phase === 'mood' && analysis && (
          <MoodScreen
            analysis={analysis}
            track={track}
            audioMode={audioMode}
            muted={muted}
            onToggleMute={toggleMute}
            onRetryAudio={retryAudio}
            onNext={() => setPhase('choice')}
            onEnd={endSession}
          />
        )}

        {phase === 'choice' && (
          <ChoiceScreen onAssist={chooseAssist} onSelfBrowse={chooseSelfBrowse} />
        )}

        {(phase === 'assist' || phase === 'selfBrowsing') && analysis && (
          <WaitingForStaffScreen
            mode={phase === 'assist' ? 'assist' : 'self'}
            analysis={analysis}
            serving={serving}
            muted={muted}
            onToggleMute={toggleMute}
            onCancelAssist={cancelAssist}
            onCallStaff={chooseAssist}
            onEnd={endSession}
          />
        )}

        {phase === 'error' && errorMessage && (
          <ErrorScreen message={errorMessage} onRetry={resetAll} />
        )}
      </main>
    </div>
  )
}
