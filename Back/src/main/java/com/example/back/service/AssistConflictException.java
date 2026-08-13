package com.example.back.service;

/**
 * 이미 다른 직원이 점유한 세션을 가로채려 한 경우. 409 로 나간다.
 * <p>
 * 중복 응대 방지는 {@code Session.acceptAssist()} 가 아니라 여기서 판정한다.
 * 도메인은 "누가 점유 중인가"만 들고 있고, 그것을 거부 사유로 쓸지는 직원 화면의
 * 정책이기 때문이다. (Session#acceptAssist javadoc 참고)
 */
public class AssistConflictException extends RuntimeException {

    public AssistConflictException(String staffName) {
        super("이미 %s 님이 응대 중인 고객입니다.".formatted(staffName));
    }
}
