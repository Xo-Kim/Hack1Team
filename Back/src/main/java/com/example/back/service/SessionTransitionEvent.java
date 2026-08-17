package com.example.back.service;

import com.example.back.domain.SessionEvent;

/**
 * 세션 상태가 바뀌었음을 애플리케이션 내부에 알리는 이벤트.
 * <p>
 * {@link SessionEvent} 를 그대로 쓰지 않고 감싸는 이유는 {@code mirrorLabel} 때문이다.
 * 알림에는 "2F 피팅룸 A" 처럼 사람이 읽는 이름이 필요한데, 세션이 이미 만료돼
 * 저장소에서 사라진 뒤에 알림이 나가는 경우가 있어 조회로 채울 수 없다.
 * 전이 시점의 값을 복사해 둔다.
 * <p>
 * 발행은 {@link SessionStore#save}, 수신은 {@link StaffNotifier} 다. 저장소가
 * 알림 컴포넌트를 직접 알지 않도록 스프링 이벤트를 사이에 둔다 — 나중에 알림 방식이
 * SSE 에서 WebSocket 으로 바뀌어도 저장소는 그대로다.
 */
public record SessionTransitionEvent(SessionEvent event, String mirrorLabel) {
}
