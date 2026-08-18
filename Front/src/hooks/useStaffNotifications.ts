import { useEffect, useRef, useState } from 'react'
import { staff } from '../api'
import type { NotificationType, StaffNotification } from '../types'

/** 서버가 보내는 이벤트 이름 → 타입. 서버의 소문자 이벤트명과 짝을 맞춘다. */
const EVENT_NAMES: Record<string, NotificationType> = {
  assist_requested: 'ASSIST_REQUESTED',
  assist_cancelled: 'ASSIST_CANCELLED',
  assist_accepted: 'ASSIST_ACCEPTED',
  assist_released: 'ASSIST_RELEASED',
  self_browsing: 'SELF_BROWSING',
  session_closed: 'SESSION_CLOSED',
}

export type ConnectionState = 'connecting' | 'live' | 'polling'

interface Options {
  /** 알림이 올 때마다 호출. 목록을 다시 불러오는 용도. */
  onChange: () => void
  /** 새 도움 요청이 왔을 때. 토스트·소리를 띄운다. */
  onAssistRequest?: (n: StaffNotification) => void
}

/**
 * 직원 화면의 실시간 알림 구독.
 * <p>
 * <b>SSE 가 끊겨도 화면은 살아 있어야 한다.</b> 매장 프록시가 스트림을 막는 경우가
 * 있으므로, 연결이 안 되면 폴링으로 내려앉고 상태를 화면에 표시한다. 실패를 조용히
 * 삼키면 직원은 알림이 오지 않는 이유를 알 방법이 없다.
 * <p>
 * EventSource 는 끊기면 브라우저가 알아서 재연결한다. 우리가 할 일은 그 사이에
 * 폴링으로 버티는 것뿐이다.
 */
export function useStaffNotifications({ onChange, onAssistRequest }: Options) {
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  const [last, setLast] = useState<StaffNotification | null>(null)

  // 콜백이 매 렌더마다 새로 만들어져도 구독이 끊기지 않도록 ref 에 담아 둔다.
  const handlers = useRef({ onChange, onAssistRequest })
  handlers.current = { onChange, onAssistRequest }

  useEffect(() => {
    const source = new EventSource(staff.notificationsUrl)

    source.addEventListener('connected', () => setConnection('live'))

    source.onerror = () => {
      // EventSource 는 재연결을 스스로 시도한다. 그동안은 폴링이 받쳐 준다.
      setConnection('polling')
    }

    for (const [eventName, type] of Object.entries(EVENT_NAMES)) {
      source.addEventListener(eventName, (e) => {
        setConnection('live')
        let payload: StaffNotification
        try {
          payload = JSON.parse((e as MessageEvent).data)
        } catch {
          return
        }
        setLast(payload)
        handlers.current.onChange()
        if (type === 'ASSIST_REQUESTED') {
          handlers.current.onAssistRequest?.(payload)
        }
      })
    }

    return () => source.close()
  }, [])

  // 폴링 폴백. SSE 가 살아 있어도 느린 주기로 한 번씩 맞춰 준다 —
  // 알림 한 건을 놓쳐도 목록이 영원히 어긋나 있지는 않게.
  useEffect(() => {
    const interval = connection === 'live' ? 15000 : 3000
    const timer = window.setInterval(() => handlers.current.onChange(), interval)
    return () => window.clearInterval(timer)
  }, [connection])

  return { connection, last }
}
