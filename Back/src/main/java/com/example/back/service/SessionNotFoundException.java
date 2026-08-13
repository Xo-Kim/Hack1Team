package com.example.back.service;

/**
 * 세션이 없거나 만료된 경우. {@code ApiExceptionHandler} 가 404 로 변환한다.
 * <p>
 * 서비스가 {@code Optional} 을 돌려주고 컨트롤러마다 404 를 조립하던 방식을 대체한다.
 * 컨트롤러가 둘로 갈라진 이상 같은 응답을 두 곳에서 만들면 반드시 문구가 어긋난다.
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("세션이 만료되었거나 존재하지 않습니다. 처음부터 다시 시작해 주세요. (sessionId=%s)"
                .formatted(sessionId));
    }
}
