import { useEffect, useRef } from 'react'

/**
 * 무입력이 지속되면 콜백을 부른다. (PRD F-13 "타임아웃 또는 터치로 대기 화면 복귀")
 *
 * 이게 없으면 고객이 그냥 자리를 떠났을 때 조명과 음악이 영원히 돌아간다.
 */
export function useIdleTimeout(active: boolean, ms: number, onIdle: () => void): void {
  const callback = useRef(onIdle)

  useEffect(() => {
    callback.current = onIdle
  }, [onIdle])

  useEffect(() => {
    if (!active) return

    let timer = 0
    const arm = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(() => callback.current(), ms)
    }

    const events = ['pointerdown', 'pointermove', 'keydown', 'touchstart', 'wheel'] as const
    events.forEach((e) => window.addEventListener(e, arm, { passive: true }))
    arm()

    return () => {
      window.clearTimeout(timer)
      events.forEach((e) => window.removeEventListener(e, arm))
    }
  }, [active, ms])
}
