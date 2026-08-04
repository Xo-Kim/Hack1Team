import type { MoodAnalysis, MusicTrack } from '../types'
import { swatch } from '../lighting'
import type { CameraState } from '../hooks/useCamera'
import type { AudioMode } from '../audio/moodAudio'

export function IdleScreen({
  cameraState,
  cameraError,
  onStart,
  onRetry,
}: {
  cameraState: CameraState
  cameraError: string | null
  onStart: () => void
  onRetry: () => void
}) {
  const blocked = cameraState === 'denied' || cameraState === 'unavailable'

  return (
    <div className="overlay overlay--center">
      <p className="eyebrow">MCM · MOOD MIRROR</p>
      <h1 className="display">거울 앞에 서 주세요</h1>
      <p className="lede">
        착장을 읽어 이 공간의 조명과 소리를 맞춥니다.
      </p>

      {blocked ? (
        <div className="notice notice--error">
          <p>{cameraError}</p>
          <button className="btn btn--ghost" onClick={onRetry}>
            다시 시도
          </button>
        </div>
      ) : (
        <button className="btn btn--primary" onClick={onStart} disabled={cameraState !== 'ready'}>
          {cameraState === 'ready' ? '시작하기' : '카메라 준비 중…'}
        </button>
      )}

      <p className="consent">
        촬영된 이미지는 분석에만 사용되며 저장되지 않습니다.
        <br />
        얼굴 정보나 개인 식별 정보를 수집하지 않습니다.
      </p>
    </div>
  )
}

export function Countdown({ value }: { value: number }) {
  return (
    <div className="overlay overlay--center">
      <div key={value} className="countdown">
        {value}
      </div>
      <p className="lede">잠시 그대로 있어 주세요</p>
    </div>
  )
}

const STEPS = ['분위기를 읽는 중', '컬러 팔레트 추출', '조명 세팅', '음향 매칭']

export function Analyzing({ step }: { step: number }) {
  return (
    <div className="overlay overlay--center">
      <div className="pulse" />
      <ul className="steps">
        {STEPS.map((label, i) => (
          <li
            key={label}
            className={i < step ? 'step step--done' : i === step ? 'step step--active' : 'step'}
          >
            {label}
          </li>
        ))}
      </ul>
    </div>
  )
}

function formatDuration(sec: number): string {
  if (!sec || sec < 0) return '—'
  const m = Math.floor(sec / 60)
  const s = String(sec % 60).padStart(2, '0')
  return `${m}:${s}`
}

export function ConceptCard({
  analysis,
  track,
  audioMode,
  muted,
  serving,
  fallback,
  elapsedMs,
  onToggleMute,
  onToggleServing,
  onRetryAudio,
  onReset,
}: {
  analysis: MoodAnalysis
  track: MusicTrack | null
  audioMode: AudioMode
  muted: boolean
  serving: boolean
  /** true = LLM 실패로 사전 정의 프리셋이 적용됨 */
  fallback: boolean
  elapsedMs: number | null
  onToggleMute: () => void
  onToggleServing: () => void
  onRetryAudio: () => void
  onReset: () => void
}) {
  const { lighting, outfit, music, conceptName, styleComment } = analysis

  return (
    <div className="overlay overlay--bottom">
      <div className="concept">
        <div className="concept__head">
          <p className="eyebrow">SCENE</p>
          {/*
            이 연출이 실제 AI 분석 결과인지, 폴백 프리셋인지 한눈에 보여준다.
            없으면 "AI가 도는 것처럼 보이지만 사실 프리셋"인 상태를 구분할 수 없다.
          */}
          <span className={`srcbadge ${fallback ? 'srcbadge--fallback' : 'srcbadge--ai'}`}>
            {fallback ? '폴백 프리셋' : 'AI 분석'}
            {elapsedMs != null && !fallback && ` · ${(elapsedMs / 1000).toFixed(1)}s`}
          </span>
        </div>
        <h2 className="display display--sm">{conceptName}</h2>

        {styleComment && <p className="comment">{styleComment}</p>}

        <div className="palette">
          {outfit.palette.map((c) => (
            <span key={c} className="chip">
              <i className="chip__dot" style={{ background: swatch(c) }} />
              {c}
            </span>
          ))}
        </div>

        <dl className="meta">
          <div>
            <dt>조명</dt>
            <dd>
              {lighting.colorTemperatureK}K · {Math.round(lighting.brightness * 100)}%
            </dd>
          </div>
          <div>
            <dt>음향</dt>
            {/*
              key/scale/bpm 은 절차적 앰비언스가 실제로 소비하는 값이다.
              실제 음원을 재생 중일 때는 그 곡과 아무 관련이 없으므로 표시하면 거짓말이 된다.
            */}
            <dd>
              {audioMode === 'track' && track
                ? `${music.genreHint} · ${formatDuration(track.durationSec)}`
                : `${music.key} ${music.scale} · ${music.bpm} BPM`}
            </dd>
          </div>
          <div>
            <dt>무드</dt>
            <dd>{analysis.mood}</dd>
          </div>
        </dl>

        {/*
          CC 라이선스는 대부분 저작자 표시를 요구한다. 아티스트명과 출처 링크는
          장식이 아니라 라이선스 준수 요건이므로 지우지 말 것.
        */}
        {audioMode === 'track' && track && (
          <>
            <p className="track">
              <span className="track__note">♪</span>
              {track.shareUrl ? (
                <a href={track.shareUrl} target="_blank" rel="noreferrer">
                  {track.title} — {track.artist}
                </a>
              ) : (
                <span>
                  {track.title} — {track.artist}
                </span>
              )}
              {/* reason 이 있으면 GPT 가 직접 고른 곡, 없으면 검색 1위 폴백이다. */}
              <span className={`track__src ${track.reason ? 'track__src--ai' : ''}`}>
                {track.reason ? 'AI 선곡' : 'Jamendo'}
              </span>
            </p>
            {track.reason && <p className="track__reason">{track.reason}</p>}
          </>
        )}
        {/*
          브라우저 자동재생 정책 때문에 첫 시도가 막힐 수 있다. 그때 조용히 두면
          "소리도 안 나고 이유도 모르는" 상태가 되므로 버튼으로 재시도를 제안한다.
          버튼 클릭은 사용자 제스처라 정책을 통과한다.
        */}
        {audioMode === 'none' && (
          <p className="track track--muted">
            <span className="track__note">♪</span>
            <span>소리를 재생하지 못했습니다</span>
            <button className="btn btn--ghost btn--tiny" onClick={onRetryAudio}>
              소리 다시 시도
            </button>
          </p>
        )}
        {audioMode === 'synth' && (
          <p className="track">
            <span className="track__note">♪</span>
            <span>무드 기반 생성 앰비언스</span>
          </p>
        )}

        <div className="actions">
          <button
            className={`btn btn--ghost ${muted ? 'is-muted' : ''}`}
            onClick={onToggleMute}
            aria-pressed={muted}
          >
            {muted ? '소리 켜기' : '음소거'}
          </button>
          <button className="btn btn--ghost" onClick={onToggleServing}>
            {serving ? '응대 종료 (음량 복귀)' : '직원 도움 받기'}
          </button>
          <button className="btn btn--ghost" onClick={onReset}>
            다시 하기
          </button>
        </div>
      </div>
    </div>
  )
}

export function ErrorOverlay({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="overlay overlay--center">
      <div className="notice notice--error">
        <p>{message}</p>
        <button className="btn btn--ghost" onClick={onRetry}>
          다시 시도
        </button>
      </div>
    </div>
  )
}
