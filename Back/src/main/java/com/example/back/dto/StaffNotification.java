package com.example.back.dto;

import java.time.Instant;

/**
 * 직원 단말로 밀어 보내는 알림 1건.
 * <p>
 * 응대 카드 전체를 싣지 않는다. 카드에는 제품 추천이 들어가는데 그건 세션당 한 번
 * LLM 랭킹을 돌려야 하는 비싼 연산이라, 알림마다 만들면 무료 등급 한도를 즉시 넘긴다.
 * 알림은 "무엇이 바뀌었는지"만 알리고, 상세는 직원이 카드를 열 때 가져간다.
 *
 * @param type       무엇이 일어났는지. 직원 화면이 토스트 문구를 고르는 데 쓴다
 * @param mirrorLabel 사람이 읽는 미러 이름. 알림에 "2F 피팅룸 A" 를 띄우기 위해 넣는다.
 *                    사라진 세션도 알림은 나가야 하므로 조회에 의존하지 않고 값을 복사한다
 * @param reminder   60초 넘게 미확인이라 다시 보낸 알림인지
 */
public record StaffNotification(
        Type type,
        String sessionId,
        String mirrorId,
        String mirrorLabel,
        String state,
        long waitingSeconds,
        boolean reminder,
        Instant occurredAt
) {

    public enum Type {
        /** 고객이 직원 도움을 요청했다. 새 대기 건. */
        ASSIST_REQUESTED,
        /** 고객이 요청을 철회했다. 대기 목록에서 내려야 한다. */
        ASSIST_CANCELLED,
        /** 다른 직원이 응대를 시작했다. 중복 응대 방지 표시. */
        ASSIST_ACCEPTED,
        /** 직원이 응대를 놓았다. 다시 대기열로. */
        ASSIST_RELEASED,
        /** 직원이 응대를 마쳤다. 목록에서 내리되 세션은 계속 살아 있다. */
        ASSIST_FINISHED,
        /** 고객이 혼자 보기를 선택했다. 접근하지 말 것. */
        SELF_BROWSING,
        /** 세션이 끝났다. 알림을 내려야 한다. */
        SESSION_CLOSED
    }
}
