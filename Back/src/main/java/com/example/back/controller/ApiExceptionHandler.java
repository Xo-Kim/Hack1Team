package com.example.back.controller;

import com.example.back.domain.IllegalStateTransitionException;
import com.example.back.dto.ApiPayloads.ErrorResponse;
import com.example.back.service.AssistConflictException;
import com.example.back.service.SessionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 고객·직원 컨트롤러가 공유하는 예외 → HTTP 상태 변환.
 * <p>
 * 컨트롤러마다 {@code @ExceptionHandler} 를 두면 같은 오류가 경로에 따라 다른 코드와
 * 문구로 나간다. 실제로 그런 일이 있었다 — 한쪽은 잘못된 이미지에 400 을, 다른 쪽은
 * 같은 입력에 500 을 돌려줬다. 프론트가 두 파트로 갈라진 지금은 더 위험하므로
 * 변환 규칙을 한 곳에 모은다.
 * <p>
 * <b>{@link ResponseEntityExceptionHandler} 를 상속하는 것이 중요하다.</b>
 * 아래 {@code Exception.class} 핸들러는 {@code @RestControllerAdvice} 안에서 애플리케이션
 * 전역에 걸리기 때문에, 그냥 두면 Spring 이 던지는 {@code NoResourceFoundException} 까지
 * 삼켜서 <i>존재하지 않는 모든 경로가 404 대신 500</i> 이 된다. 부모 클래스가 그런 프레임워크
 * 예외들에 대해 더 구체적인 핸들러를 이미 갖고 있어 우선 적용되므로 문제가 사라진다.
 * 응답 형식은 {@link #handleExceptionInternal} 에서 우리 {@link ErrorResponse} 로 맞춘다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(SessionNotFoundException e) {
        log.debug("session not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("session_not_found", e.getMessage()));
    }

    /**
     * 다른 직원이 이미 점유한 세션. {@code illegal_state} 와 코드를 구분하는 이유는
     * 직원 화면이 "누가 응대 중입니다"와 "지금은 응대할 수 없는 상태입니다"를
     * 다른 문구로 띄워야 하기 때문이다.
     */
    @ExceptionHandler(AssistConflictException.class)
    public ResponseEntity<ErrorResponse> assistConflict(AssistConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("assist_conflict", e.getMessage()));
    }

    /**
     * 허용되지 않은 전이. 화면 상태와 서버 상태가 어긋난 경우이므로
     * 프론트는 409 를 받으면 세션 상태를 다시 조회해 화면을 맞춘다.
     */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ErrorResponse> illegalTransition(IllegalStateTransitionException e) {
        log.warn("illegal transition: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("illegal_state", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        // 예외 메시지에 이미지 원문이 실리지 않도록 MirrorService 에서 정제한 메시지만 쓴다.
        log.warn("bad request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("internal_error", "서버 처리 중 오류가 발생했습니다."));
    }

    /**
     * 프레임워크가 처리하는 예외(잘못된 경로·메서드·깨진 JSON 등)의 응답 본문을
     * 우리 {@link ErrorResponse} 모양으로 바꾼다. 상태 코드는 부모가 정한 값을 그대로 쓴다.
     * <p>
     * 예외 원문은 내보내지 않는다. 경로나 파싱 위치가 그대로 노출될 수 있다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        log.debug("framework exception {} → {}", ex.getClass().getSimpleName(), statusCode);
        return super.handleExceptionInternal(
                ex, describe(statusCode), headers, statusCode, request);
    }

    private static ErrorResponse describe(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return new ErrorResponse("not_found", "요청한 경로를 찾을 수 없습니다.");
        }
        if (status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
            return new ErrorResponse("method_not_allowed", "허용되지 않은 HTTP 메서드입니다.");
        }
        if (status.is4xxClientError()) {
            return new ErrorResponse("bad_request", "요청 형식이 올바르지 않습니다.");
        }
        return new ErrorResponse("internal_error", "서버 처리 중 오류가 발생했습니다.");
    }
}
