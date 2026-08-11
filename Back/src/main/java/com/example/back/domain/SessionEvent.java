package com.example.back.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 상태 전이 1건. append-only 이며 나중에 session_events 테이블로 적재된다.
 * <p>
 * SessionStore 의 세션은 TTL 로 사라지지만 이 이벤트는 남아야 한다.
 * PRD 의 핵심 지표가 전부 여기서 나오기 때문이다.
 * <ul>
 *   <li>직원 도달 시간 = ASSIST_ACCEPTED.occurredAt - ASSIST_REQUESTED.occurredAt</li>
 *   <li>무드 연출 완료율 = MOOD_ACTIVE 도달 세션 / CONSENTED 도달 세션</li>
 *   <li>촬영 실행률 = CAPTURING 도달 세션 / MOOD_ACTIVE 도달 세션</li>
 * </ul>
 */
public record SessionEvent(
        String sessionId,
        String mirrorId,
        SessionState from,
        SessionState to,
        TransitionReason reason,
        Instant occurredAt,
        Map<String, String> metadata) {

    public SessionEvent {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(occurredAt, "occurredAt");
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }
}
