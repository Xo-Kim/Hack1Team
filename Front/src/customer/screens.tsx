import { useState } from 'react'
import type { AudioMode } from '../audio/moodAudio'
import type { CameraState } from '../hooks/useCamera'
import type { MoodAnalysis, MusicTrack } from '../types'

/**
 * 고객 미러 화면들. 와이어프레임 n3·n4·n6·n9·n12·n14 에 대응한다.
 *
 * <b>이 파일에는 제품명·가격·추천이 한 글자도 없다.</b> 서버가 고객 경로로 그 데이터를
 * 내려주지 않으므로 넣으려 해도 넣을 수 없지만, 원칙이 코드에서도 보이도록 적어 둔다.
 */

// --------------------------------------------------------------- n3 대기 화면

export function IdleScreen({ onStart }: { onStart: () => void }) {
  return (
    <div className="screen screen--center">
      <div className="brandmark">MCM</div>
      <div className="stack stack--tight">
        <h1 className="title">무드 미러</h1>
        <p className="lead">착장을 읽고 조명과 음악을 맞춥니다</p>
      </div>
      <button className="btn btn--primary" onClick={onStart}>
        시작
      </button>
    </div>
  )
}

// ----------------------------------------------------------- n4 동의 화면

/**
 * 촬영·분석 동의.
 * <p>
 * 와이어프레임에는 "사진 보관 — QR 코드로 제공되며 24시간 뒤 자동 삭제" 항목이
 * 있었다. <b>그 기능은 삭제됐다.</b> 서비스는 사진을 촬영·생성·전송·저장하지 않는다.
 * 없는 정책에 동의를 받으면 그 자체로 거짓 고지가 되므로, 대신 "아무것도 남기지
 * 않는다"를 명시하는 항목으로 바꿨다.
 */
export function ConsentScreen({
  onAccept,
  onCancel,
}: {
  onAccept: () => void
  onCancel: () => void
}) {
  const [agreed, setAgreed] = useState(false)

  return (
    <div className="screen screen--sheet">
      <h2 className="title">촬영 및 분석 동의</h2>

      <div className="stack">
        <section className="note">
          <h3>카메라 촬영</h3>
          <p>
            미러 스크린에서 당신의 착장을 촬영합니다. 촬영은 당신이 시작을 선택했을 때만
            진행되며, 자동 촬영은 이루어지지 않습니다.
          </p>
        </section>

        <section className="note">
          <h3>AI 분석</h3>
          <p>
            촬영된 이미지는 스타일 분석에만 사용됩니다. 분석 결과는 당신의 무드와 어울리는
            조명 및 음악 연출에 반영됩니다.
          </p>
        </section>

        <section className="note note--accent">
          <h3>사진은 남지 않습니다</h3>
          <p>
            이미지는 분석하는 동안 메모리에서만 처리되고 즉시 사라집니다. 저장하거나
            전송하지 않으며, 서비스가 당신의 사진을 만들어 보관하는 일도 없습니다.
          </p>
        </section>
      </div>

      <label className="check">
        <input
          type="checkbox"
          checked={agreed}
          onChange={(e) => setAgreed(e.target.checked)}
        />
        <span>촬영 및 분석에 동의합니다</span>
      </label>

      <div className="row row--end">
        <button className="btn" onClick={onCancel}>
          취소
        </button>
        <button className="btn btn--primary" disabled={!agreed} onClick={onAccept}>
          동의하고 진행
        </button>
      </div>
    </div>
  )
}

// ------------------------------------------------------- n6 카메라 권한 안내

export function PermissionScreen({
  cameraState,
  error,
  onRetry,
  onContinue,
  onCancel,
}: {
  cameraState: CameraState
  error: string | null
  onRetry: () => void
  onContinue: () => void
  onCancel: () => void
}) {
  const ready = cameraState === 'ready'

  return (
    <div className="screen screen--sheet">
      <h2 className="title">카메라 권한이 필요합니다</h2>
      <p className="muted">
        착장을 분석하려면 카메라 접근을 허용해 주세요. 브라우저 주소창의 권한 아이콘에서
        다시 허용할 수 있습니다.
      </p>

      {error && <p className="alert">{error}</p>}

      <section className="note">
        <h3>카메라가 열리지 않는다면</h3>
        <p>
          <code>localhost</code> 또는 https 로 접속했는지 확인해 주세요. 그 외 주소에서는
          브라우저가 카메라를 차단합니다.
        </p>
      </section>

      <div className="row row--end">
        <button className="btn" onClick={onCancel}>
          처음으로
        </button>
        <button className="btn" onClick={onRetry}>
          다시 요청
        </button>
        <button className="btn btn--primary" disabled={!ready} onClick={onContinue}>
          {ready ? '촬영 시작' : '권한 대기 중'}
        </button>
      </div>
    </div>
  )
}

// ------------------------------------------------------------ 카운트다운

export function CountdownScreen({ value }: { value: number }) {
  return (
    <div className="screen screen--center countdown">
      <div className="countdown__num" key={value}>
        {value}
      </div>
      <p className="muted">잠시 후 촬영합니다 — 편하게 서 계세요</p>
    </div>
  )
}

// ---------------------------------------------------------- n9 분석 진행

const STEPS = ['착장과 분위기를 읽는 중', '컬러 팔레트 추출 중', '조명과 음악 세팅 중']

export function AnalyzingScreen({ step }: { step: number }) {
  return (
    <div className="screen screen--center">
      <h2 className="title">당신의 무드를 분석하고 있습니다</h2>

      <ul className="steps">
        {STEPS.map((label, i) => (
          <li key={label} className={i <= step ? 'is-active' : ''}>
            {label}
          </li>
        ))}
      </ul>

      <div className="progress">
        <span className="progress__bar" />
      </div>
    </div>
  )
}

// ---------------------------------------------------------- n12 무드 연출

export function MoodScreen({
  analysis,
  track,
  audioMode,
  muted,
  onToggleMute,
  onRetryAudio,
  onNext,
  onEnd,
}: {
  analysis: MoodAnalysis
  track: MusicTrack | null
  audioMode: AudioMode
  muted: boolean
  onToggleMute: () => void
  onRetryAudio: () => void
  onNext: () => void
  onEnd: () => void
}) {
  return (
    <div className="screen screen--bottom">
      <div className="stack stack--tight">
        <p className="label">Mood ready</p>
        <h1 className="concept">{analysis.conceptName}</h1>
        {analysis.styleComment && <p className="lead">{analysis.styleComment}</p>}
      </div>

      <div className="facts">
        <Fact label="컨셉" value={analysis.conceptName} />
        <Fact label="조명" value={analysis.lighting.sceneName} />
        <Fact label="음향" value={audioLabel(audioMode, track, analysis)} />
      </div>

      {/*
        로딩 중에는 띄우지 않는다. 음원 로딩은 실측 3~4초인데 그 사이 이 버튼을 누르면
        먼저 걸려 있던 재생이 중단되고, 그 중단이 '음원 실패'로 읽혀 신스가 켜졌다.
      */}
      {(audioMode === 'blocked' || audioMode === 'none') && (
        <button className="btn btn--sm" onClick={onRetryAudio}>
          음악 재생하기
        </button>
      )}

      {/*
        실제로 그 곡이 울릴 때만 표기한다. CC 저작자 표시는 저작물을 사용할 때의
        의무이고, 신스로 폴백했는데 곡 크레딧이 남아 있으면 화면이 거짓말을 한다.
      */}
      {audioMode === 'track' && track && (
        <p className="credit">
          {track.title} — {track.artist}{' '}
          <a href={track.shareUrl} target="_blank" rel="noreferrer">
            {track.license}
          </a>
        </p>
      )}

      <div className="row">
        <button className="btn btn--primary" onClick={onNext}>
          다음
        </button>
        <button className="btn btn--sm" onClick={onToggleMute}>
          {muted ? '음악 켜기' : '음악 끄기'}
        </button>
        <button className="btn btn--sm" onClick={onEnd}>
          마치기
        </button>
      </div>
    </div>
  )
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="fact">
      <span className="label">{label}</span>
      <span className="fact__value">{value}</span>
    </div>
  )
}

/**
 * 음향 표시.
 * <p>
 * 실제 음원이 재생 중일 때 <code>C minor · 95 BPM</code> 같은 절차적 생성기의
 * 파라미터를 띄우면 거짓말이 된다. 재생 방식에 따라 표시를 갈라야 한다.
 */
function audioLabel(mode: AudioMode, track: MusicTrack | null, analysis: MoodAnalysis): string {
  if (mode === 'track' && track) return track.title
  if (mode === 'loading') return '음향 준비 중'
  if (mode === 'blocked') return '재생 대기'
  if (mode === 'synth') return `${analysis.music.key} ${analysis.music.scale} · ${analysis.music.bpm} BPM`
  return '재생 대기'
}

// ---------------------------------------------------------- n14 응대 선택

/**
 * 응대 선택.
 * <p>
 * <b>두 버튼은 반드시 같은 크기·같은 위계여야 한다.</b> 거절할 수 있어야 수락이
 * 진심이 되고, 진심으로 받은 응대만 공유된다. 이건 배려 기능이 아니라 전략 요소다.
 * 한쪽을 크게 만들거나 색으로 강조하지 말 것.
 */
export function ChoiceScreen({
  onAssist,
  onSelfBrowse,
}: {
  onAssist: () => void
  onSelfBrowse: () => void
}) {
  return (
    <div className="screen screen--center">
      <h2 className="title">어떻게 도와드릴까요?</h2>

      <div className="choices">
        <button className="choice" onClick={onAssist}>
          <span className="choice__title">직원 도움 받기</span>
          <span className="choice__desc">스타일링을 함께 봐 드릴게요</span>
        </button>
        <button className="choice" onClick={onSelfBrowse}>
          <span className="choice__title">혼자 볼게요</span>
          <span className="choice__desc">연출은 그대로 유지됩니다</span>
        </button>
      </div>
    </div>
  )
}

// ------------------------------------------------- 응대 대기 / 자율 관람

/**
 * 응대 대기 / 자율 관람.
 * <p>
 * <b>직원 이름을 쓰지 않는다.</b> 예전에는 "○○ 님이 곧 도착합니다"였는데, 그 이름은
 * 직원이 단말에 손으로 적은 검증되지 않은 값이었다. 비워 두면 문장이 깨지고, 채우게
 * 하면 응대할 때마다 입력을 강요한다. 고객이 알아야 하는 것은 누가 오는지가 아니라
 * 기다리면 된다는 사실이므로 문구를 상태 기반으로 바꿨다.
 */
export function WaitingForStaffScreen({
  mode,
  analysis,
  serving,
  muted,
  onToggleMute,
  onCancelAssist,
  onCallStaff,
  onEnd,
}: {
  mode: 'assist' | 'self'
  analysis: MoodAnalysis
  /** 직원이 응대를 잡았는지 (서버 상태 ASSIST_ACCEPTED). */
  serving: boolean
  muted: boolean
  onToggleMute: () => void
  onCancelAssist: () => void
  onCallStaff: () => void
  onEnd: () => void
}) {
  return (
    <div className="screen screen--bottom">
      <div className="stack stack--tight">
        <p className="label">{analysis.conceptName}</p>
        <h2 className="title">
          {mode === 'assist'
            ? serving
              ? '잠시만 기다려 주세요'
              : '곧 도와드리겠습니다'
            : '편하게 둘러보세요'}
        </h2>
        <p className="muted">
          {mode === 'assist'
            ? serving
              ? '직원이 곧 도착합니다 · 연출은 그대로 유지됩니다'
              : '연출은 그대로 유지됩니다'
            : '필요하시면 언제든 직원을 부르실 수 있어요'}
        </p>
      </div>

      <div className="row">
        {mode === 'assist' && !serving && (
          <button className="btn" onClick={onCancelAssist}>
            요청 취소
          </button>
        )}
        {mode === 'self' && (
          <button className="btn" onClick={onCallStaff}>
            직원 부르기
          </button>
        )}
        <button className="btn" onClick={onToggleMute}>
          {muted ? '음악 켜기' : '음악 끄기'}
        </button>
        <button className="btn" onClick={onEnd}>
          마치기
        </button>
      </div>
    </div>
  )
}

// ------------------------------------------------------------------ 오류

export function ErrorScreen({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="screen screen--center">
      <h2 className="title">잠시 문제가 있었습니다</h2>
      <p className="alert">{message}</p>
      <button className="btn btn--primary" onClick={onRetry}>
        처음부터 다시
      </button>
    </div>
  )
}
