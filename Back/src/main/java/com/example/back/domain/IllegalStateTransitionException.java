package com.example.back.domain;

/**
 * 허용되지 않은 상태 전이 시도.
 * MirrorController 의 @ExceptionHandler 에서 409 로 매핑한다.
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final SessionState from;
    private final SessionState to;

    public IllegalStateTransitionException(SessionState from, SessionState to) {
        super("허용되지 않은 상태 전이입니다: %s -> %s".formatted(from, to));
        this.from = from;
        this.to = to;
    }

    public SessionState from() {
        return from;
    }

    public SessionState to() {
        return to;
    }
}
