package com.example.back.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 미러 세션의 상태. 허용 전이는 이 enum 이 단일 원천이며,
 * 직원 화면으로 나가는 문자열도 여기 이름을 그대로 쓴다.
 *
 * <pre>
 * IDLE → CONSENTED → ANALYZING → MOOD_ACTIVE
 *                                   ├→ ASSIST_REQUESTED ⇄ ASSIST_ACCEPTED
 *                                   └→ SELF_BROWSING
 *
 * (연출 이후 모든 상태) → ENDED     // 응대 완료 등 정상 종료
 * (모든 비종료 상태)   → EXPIRED   // 타임아웃 · 리셋
 * </pre>
 *
 * 프로토타입의 3초 카운트다운은 상태로 두지 않는다. 서버는 카운트다운을 관측할 수단이
 * 없고 이미지가 도착해야 비로소 알 수 있으므로, 클라이언트 UI 단계로 남긴다.
 * <p>
 * <b>ENDED 와 EXPIRED 를 나누는 이유는 지표다.</b> 핵심 지표가 완주 세션 수인데
 * 종료 경로가 하나뿐이면 "경험을 마친 고객"과 "그냥 떠난 고객"이 구분되지 않는다.
 */
public enum SessionState {

    /** 대기 화면. 세션은 생성됐으나 고객이 아직 동의하지 않음. */
    IDLE,
    /** 촬영·분석 동의 완료. */
    CONSENTED,
    /** 분석용 스틸컷 전송 후 무드 분석 진행 중. */
    ANALYZING,
    /** 조명·음악·컨셉명 연출 적용됨. 분석 실패 시 프리셋으로도 이 상태에 도달한다. */
    MOOD_ACTIVE,
    /** 고객이 직원 도움 받기를 선택. 직원 화면 대기 목록에 오른다. */
    ASSIST_REQUESTED,
    /** 특정 직원이 응대를 시작함. 음악 볼륨 하향, 조명 유지. */
    ASSIST_ACCEPTED,
    /** 고객이 혼자 볼게요를 선택. 직원 알림을 보내지 않는다. */
    SELF_BROWSING,
    /** 정상 종료. 응대를 마쳤거나 고객이 경험을 끝냄. */
    ENDED,
    /** 무입력 타임아웃 또는 명시적 리셋. 미응대 알림은 취소된다. */
    EXPIRED;

    private static final Set<SessionState> TERMINAL = EnumSet.of(ENDED, EXPIRED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * 직원 화면이 알아야 하는 상태인지.
     * <p>
     * 종료 상태가 포함된 것은 <b>알림을 내리기 위해서</b>다. 대기 목록에 올리라는 뜻이
     * 아니므로, 목록 조회는 여기에 더해 {@link #isTerminal()} 을 따로 걸러야 한다.
     */
    public boolean isStaffVisible() {
        return this == ASSIST_REQUESTED || this == ASSIST_ACCEPTED
                || this == SELF_BROWSING || isTerminal();
    }

    /**
     * 이 상태에서 진입 가능한 다음 상태 집합.
     * 종료 상태를 제외한 모든 상태에서 EXPIRED 로 갈 수 있다.
     */
    public Set<SessionState> allowedNext() {
        Set<SessionState> next = switch (this) {
            case IDLE -> EnumSet.of(CONSENTED);
            case CONSENTED -> EnumSet.of(ANALYZING);
            case ANALYZING -> EnumSet.of(MOOD_ACTIVE);
            case MOOD_ACTIVE -> EnumSet.of(ASSIST_REQUESTED, SELF_BROWSING, ENDED);
            // 고객이 요청을 철회하면 MOOD_ACTIVE 로 되돌아간다.
            case ASSIST_REQUESTED -> EnumSet.of(ASSIST_ACCEPTED, MOOD_ACTIVE, ENDED);
            // 직원이 응대를 놓으면(release) 다시 대기열로 돌아간다.
            case ASSIST_ACCEPTED -> EnumSet.of(ASSIST_REQUESTED, ENDED);
            // 자율 관람 중에도 마음이 바뀌면 직원을 부를 수 있다.
            case SELF_BROWSING -> EnumSet.of(ASSIST_REQUESTED, ENDED);
            case ENDED, EXPIRED -> EnumSet.noneOf(SessionState.class);
        };
        if (!isTerminal()) {
            next.add(EXPIRED);
        }
        return Collections.unmodifiableSet(next);
    }

    public boolean canTransitionTo(SessionState target) {
        return allowedNext().contains(target);
    }
}
