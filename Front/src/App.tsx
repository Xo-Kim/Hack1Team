import { useCallback, useEffect, useRef, useState } from 'react'
import * as api from './api'
import { effectClass, lightingToCssVars } from './lighting'
import { useCamera } from './hooks/useCamera'
import { useIdleTimeout } from './hooks/useIdleTimeout'
import { MoodAudio, type AudioMode } from './audio/moodAudio'
import { Analyzing, ConceptCard, Countdown, ErrorOverlay, IdleScreen } from './components/Overlays'
import { StaffPanel, type RecState } from './components/StaffPanel'
import type { HealthResponse, MoodAnalysis, MusicTrack, RecommendResponse } from './types'
import './styles.css'

type Phase = 'idle' | 'countdown' | 'analyzing' | 'scene' | 'error'

const COUNTDOWN_FROM = 3
const SERVING_VOLUME = 0.18
const NORMAL_VOLUME = 0.55

/** 무입력 3분이면 대기 화면으로 돌아간다. (PRD F-13) */
const IDLE_TIMEOUT_MS = 3 * 60 * 1000

function App() {
  const { videoRef, state: cameraState, error: cameraError, capture, retry } = useCamera()

  const [phase, setPhase] = useState<Phase>('idle')
  const [count, setCount] = useState(COUNTDOWN_FROM)
  const [step, setStep] = useState(0)
  const [analysis, setAnalysis] = useState<MoodAnalysis | null>(null)
  const [analysisFallback, setAnalysisFallback] = useState(false)
  const [analyzeMs, setAnalyzeMs] = useState<number | null>(null)
  const [track, setTrack] = useState<MusicTrack | null>(null)
  const [audioMode, setAudioMode] = useState<AudioMode>('none')
  const [muted, setMuted] = useState(false)
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [serving, setServing] = useState(false)

  const [recState, setRecState] = useState<RecState>('idle')
  const [recData, setRecData] = useState<RecommendResponse | null>(null)
  const [recError, setRecError] = useState<string | null>(null)

  const [health, setHealth] = useState<HealthResponse | null>(null)

  const audio = useRef<MoodAudio | null>(null)
  if (audio.current === null) {
    audio.current = new MoodAudio()
  }

  useEffect(() => {
    api.health().then(setHealth).catch(() => setHealth(null))
  }, [])

  // 언마운트 시 오디오 정리. 개발 중 HMR 로 소리가 겹치는 것을 막는다.
  useEffect(() => {
    const engine = audio.current
    return () => engine?.stop(0)
  }, [])

  const reset = useCallback(() => {
    audio.current?.stop()
    setPhase('idle')
    setCount(COUNTDOWN_FROM)
    setStep(0)
    setAnalysis(null)
    setAnalysisFallback(false)
    setAnalyzeMs(null)
    setTrack(null)
    setAudioMode('none')
    setMuted(false)
    setSessionId(null)
    setErrorMessage(null)
    setServing(false)
    setRecState('idle')
    setRecData(null)
    setRecError(null)
  }, [])

  // 대기 화면이 아닐 때만 감시한다. 고객이 자리를 떠나면 조명·음악이 자동으로 꺼진다.
  useIdleTimeout(phase !== 'idle', IDLE_TIMEOUT_MS, reset)

  /**
   * 촬영 → 분석 → 연출.
   * 반드시 클릭 핸들러 안에서 시작해야 AudioContext 가 살아난다.
   */
  const start = useCallback(() => {
    setErrorMessage(null)
    setPhase('countdown')
    setCount(COUNTDOWN_FROM)

    async function loadRecommendations(id: string) {
      setRecState('loading')
      setRecError(null)
      try {
        setRecData(await api.recommend(id))
        setRecState('done')
      } catch (e) {
        setRecError(e instanceof Error ? e.message : '추천을 불러오지 못했습니다.')
        setRecState('error')
      }
    }

    async function runAnalysis() {
      const dataUrl = capture()
      if (!dataUrl) {
        setErrorMessage('카메라 프레임을 읽지 못했습니다. 다시 시도해 주세요.')
        setPhase('error')
        return
      }

      setPhase('analyzing')
      setStep(0)
      const stepTimer = window.setInterval(() => setStep((s) => Math.min(s + 1, 3)), 900)

      const startedAt = performance.now()
      try {
        const res = await api.analyze(dataUrl)
        window.clearInterval(stepTimer)

        setAnalyzeMs(Math.round(performance.now() - startedAt))
        setAnalysis(res.analysis)
        setAnalysisFallback(res.fallback)
        setTrack(res.track)
        setSessionId(res.sessionId)
        setPhase('scene')

        if (res.fallback) {
          console.warn('[mood-mirror] LLM 미사용 — 폴백 프리셋:', res.note)
        }

        // 빛과 소리를 같은 시간에 걸쳐 올린다. (PRD §14.1)
        // track 이 있으면 실제 음원, 없으면 절차적 앰비언스로 자동 폴백된다.
        const engine = audio.current
        if (engine) {
          engine.setVolume(NORMAL_VOLUME, 0)
          await engine.play(res.analysis.music, res.track, res.analysis.lighting.transitionMs)
          setAudioMode(engine.mode)
        }

        // 추천은 연출이 시작된 뒤 백그라운드로 — 고객에게 지연이 보이지 않는다. (PRD §12.3)
        void loadRecommendations(res.sessionId)
      } catch (e) {
        window.clearInterval(stepTimer)
        setErrorMessage(e instanceof Error ? e.message : '분석에 실패했습니다.')
        setPhase('error')
      }
    }

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
  }, [capture])

  /** 응대 모드 — 조명은 유지하고 음량만 낮춘다. (PRD §8.1 / F-11) */
  const toggleServing = useCallback(() => {
    setServing((on) => {
      const next = !on
      audio.current?.setVolume(next ? SERVING_VOLUME : NORMAL_VOLUME, 700)
      return next
    })
  }, [])

  /** 자동재생 정책으로 첫 시도가 막혔을 때. 버튼 클릭 = 사용자 제스처라 이번엔 통과한다. */
  const retryAudio = useCallback(async () => {
    const engine = audio.current
    if (!engine || !analysis) return
    await engine.play(analysis.music, track, analysis.lighting.transitionMs)
    setAudioMode(engine.mode)
    engine.setMuted(muted)
  }, [analysis, track, muted])

  const toggleMute = useCallback(() => {
    setMuted((on) => {
      const next = !on
      audio.current?.setMuted(next)
      return next
    })
  }, [])

  const lighting = phase === 'scene' && analysis ? analysis.lighting : null

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
          <span className="statusbar__dot" data-mode={health?.llmMode ?? 'offline'} />
          <span>
            {health
              ? `${health.llmMode === 'live' ? 'LLM 연결됨' : 'LLM mock (키 미설정 — 폴백 프리셋)'}` +
                ` · ${health.musicMode === 'jamendo' ? '음원 Jamendo' : '음원 생성 앰비언스'}`
              : '백엔드 연결 안 됨'}
          </span>
          {sessionId && <span className="statusbar__session">session {sessionId.slice(0, 8)}</span>}
        </div>

        {phase === 'idle' && (
          <IdleScreen
            cameraState={cameraState}
            cameraError={cameraError}
            onStart={start}
            onRetry={retry}
          />
        )}
        {phase === 'countdown' && <Countdown value={count} />}
        {phase === 'analyzing' && <Analyzing step={step} />}
        {phase === 'scene' && analysis && (
          <ConceptCard
            analysis={analysis}
            track={track}
            audioMode={audioMode}
            muted={muted}
            serving={serving}
            fallback={analysisFallback}
            elapsedMs={analyzeMs}
            onToggleMute={toggleMute}
            onToggleServing={toggleServing}
            onRetryAudio={retryAudio}
            onReset={reset}
          />
        )}
        {phase === 'error' && errorMessage && (
          <ErrorOverlay message={errorMessage} onRetry={reset} />
        )}
      </main>

      <StaffPanel
        analysis={analysis}
        data={recData}
        state={recState}
        error={recError}
        serving={serving}
      />
    </div>
  )
}

export default App
