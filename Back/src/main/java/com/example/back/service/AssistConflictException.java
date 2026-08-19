package com.example.back.service;

/**
 * 이미 다른 직원이 점유한 세션을 가로채려 한 경우. 409 로 나간다.
 * <p>
 * 중복 응대 방지는 {@code Session.acceptAssist()} 가 아니라 여기서 판정한다.
 * 도메인은 "누가 점유 중인가"만 들고 있고, 그것을 거부 사유로 쓸지는 직원 화면의
 * 정책이기 때문이다. (Session#acceptAssist javadoc 참고)
 */
public class AssistConflictException extends RuntimeException {

    /**
     * 누가 점유 중인지는 메시지에 넣지 않는다. 이 문구는 다른 직원의 화면에 그대로
     * 뜨는데 거기서 필요한 정보는 "이미 누가 잡았다"는 사실뿐이고, 점유자가 누구인지는
     * 서버 로그에 {@code staffId} 로 남는다.
     */
    public AssistConflictException() {
        super("이미 다른 직원이 응대 중인 고객입니다.");
    }
}
