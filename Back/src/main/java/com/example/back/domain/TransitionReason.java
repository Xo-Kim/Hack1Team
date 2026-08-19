package com.example.back.domain;

/**
 * 상태 전이의 사유. session_events 로 적재되며 핵심 지표는
 * 이 사유가 붙은 이벤트의 시각 차이로 산출한다.
 */
public enum TransitionReason {
    CONSENT_GIVEN,
    ANALYSIS_STARTED,
    /** 무드 분석 성공. */
    MOOD_APPLIED,
    /** 분석 실패로 FallbackPresets 프리셋이 적용됨. */
    MOOD_FALLBACK_APPLIED,
    ASSIST_REQUESTED,
    /** 직원이 응대 시작. requestedAt 과의 차이가 직원 도달 시간. */
    ASSIST_ACCEPTED,
    /** 직원이 응대를 놓음. 세션은 다시 요청 대기로 돌아간다. */
    ASSIST_RELEASED,
    /** 직원이 응대를 마침. 세션은 연출 상태로 돌아가고 고객은 계속 머문다. */
    ASSIST_FINISHED,
    /** 고객이 요청을 철회함. */
    ASSIST_CANCELLED,
    SELF_BROWSE_SELECTED,
    COMPLETED,
    /** 미러 화면에서 명시적으로 리셋됨. */
    RESET,
    /** 무입력 시간 초과. 프로토타입의 useIdleTimeout(3분)과 같은 사유다. */
    TIMEOUT
}
