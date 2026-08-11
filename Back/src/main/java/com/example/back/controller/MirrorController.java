package com.example.back.controller;

import com.example.back.domain.IllegalStateTransitionException;
import com.example.back.domain.Session;
import com.example.back.domain.SessionState;
import com.example.back.domain.StaffAssignment;
import com.example.back.dto.ApiPayloads.AnalyzeRequest;
import com.example.back.dto.ApiPayloads.AnalyzeResponse;
import com.example.back.dto.ApiPayloads.ErrorResponse;
import com.example.back.dto.ApiPayloads.SessionStateResponse;
import com.example.back.dto.ApiPayloads.StartSessionRequest;
import com.example.back.dto.ApiPayloads.StartSessionResponse;
import com.example.back.service.JamendoClient;
import com.example.back.service.LlmClient;
import com.example.back.service.MirrorService;
import com.example.back.service.SessionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 고객 미러 화면 전용 API.
 * <p>
 * <b>이 컨트롤러는 제품 추천을 반환하지 않는다.</b> 기존 {@code GET /api/recommend/{sessionId}}
 * 는 제거됐다. 고객 화면에 AI 추천을 노출하지 않는 것이 이 서비스의 유일한 차별점인데,
 * 고객이 도달 가능한 경로에 추천 엔드포인트가 남아 있으면 그 원칙은 "프론트가 안 부르기로 한
 * 약속"에 불과해진다. 추천은 {@code /api/staff/**} 로만 나간다.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Mood Mirror", description = "고객 미러 — 세션 · 촬영 · 분석 · 연출")
public class MirrorController {

    private static final Logger log = LoggerFactory.getLogger(MirrorController.class);

    private final MirrorService mirror;
    private final LlmClient llm;
    private final SessionStore sessions;
    private final JamendoClient jamendo;

    public MirrorController(MirrorService mirror, LlmClient llm, SessionStore sessions,
                            JamendoClient jamendo) {
        this.mirror = mirror;
        this.llm = llm;
        this.sessions = sessions;
        this.jamendo = jamendo;
    }

    @GetMapping("/health")
    @Operation(
            summary = "상태 확인",
            description = """
                    현재 동작 모드를 알려준다.

                    - `llmMode`: `live` = OpenAI 키가 들어옴 / `mock` = 키 없음 → 폴백 프리셋
                    - `musicMode`: `jamendo` = 음원 검색 가능 / `synth` = 절차적 앰비언스만

                    **주의**: `live` 는 "키가 들어왔다"는 뜻이지 "키가 유효하다"는 뜻이 아니다.
                    잘못된 키도 `live` 로 표시되고 호출 시점에 401 이 난다.
                    실제 성공 여부는 서버 로그의 `analyze done ... fallback=false` 로 확인한다.
                    """)
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "llmMode", llm.isMock() ? "mock" : "live",
                "musicMode", jamendo.isEnabled() ? "jamendo" : "synth",
                "activeSessions", sessions.size()
        );
    }

    // ------------------------------------------------------------- 세션 생명주기

    @PostMapping("/sessions")
    @Operation(
            summary = "세션 시작",
            description = """
                    미러 대기 화면에서 고객이 반응하면 세션을 연다.

                    분석보다 먼저 세션을 여는 이유는 두 가지다. 직원 알림에 어느 미러인지가
                    필요하고, 동의를 거부했거나 중간에 이탈한 고객도 지표에 남겨야 한다.
                    """)
    public StartSessionResponse start(@RequestBody StartSessionRequest request) {
        Session session = sessions.start(
                request.mirrorId(), request.storeId(), request.mirrorLabel());
        sessions.save(session);
        log.info("session started sessionId={} mirrorId={}", session.id(), session.mirrorId());
        return new StartSessionResponse(session.id(), session.state().name());
    }

    @PostMapping("/sessions/{sessionId}/consent")
    @Operation(summary = "촬영·분석 동의", description = """
            동의 이후 프론트가 3·2·1 카운트다운을 진행한다.

            카운트다운은 서버 상태로 두지 않았다. 서버는 카운트다운을 관측할 수단이 없고
            이미지가 도착해야 비로소 알 수 있으므로, 클라이언트 UI 단계로 남긴다.
            """)
    public ResponseEntity<?> consent(@PathVariable String sessionId) {
        return apply(sessionId, Session::consent);
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "세션 상태 조회", description = """
            미러 화면이 폴링하는 경로. 연출 상태와 직원 응대 여부를 함께 돌려준다.

            `musicDucked` 가 true 면 직원 응대가 시작된 것이므로 음악 볼륨을 낮추고
            조명 연출은 유지한다. 클라이언트가 상태값으로 추론하지 않도록 서버가 직접 알려준다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = SessionStateResponse.class))),
            @ApiResponse(responseCode = "404", description = "세션 만료 또는 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> state(@PathVariable String sessionId) {
        return sessions.get(sessionId)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(toStateResponse(s)))
                .orElseGet(MirrorController::sessionNotFound);
    }

    @PostMapping("/sessions/{sessionId}/reset")
    @Operation(summary = "세션 리셋", description = """
            미러를 대기 화면으로 되돌린다. 미응대 알림이 걸려 있으면 함께 취소된다.
            """)
    public ResponseEntity<?> reset(@PathVariable String sessionId) {
        return apply(sessionId, Session::reset);
    }

    // ----------------------------------------------------------------- 분석·연출

    @PostMapping("/analyze")
    @Operation(
            summary = "착장 분석 + 조명·음향 생성",
            description = """
                    촬영 이미지를 받아 조명 스펙·음향 스펙·재생할 음원을 돌려준다.

                    내부적으로 LLM 을 2회 호출한다 (Vision 분석 → 후보 곡 중 선곡).
                    응답 시간은 실측 5~7초.

                    **이미지는 저장되지 않는다.** 메모리에서만 처리하고 폐기하며,
                    세션에 남는 것은 분석 결과 텍스트뿐이다.

                    LLM 이 실패해도 200 을 돌려준다. 이때 `fallback: true` 이고
                    사전 정의 프리셋 5종 중 하나가 적용된다 (이미지 해시 기준이라 같은 사진은 같은 결과).
                    고객 경험은 성공과 동일해야 하므로 화면에는 차이를 드러내지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "분석 성공. `fallback` 값으로 AI 결과인지 프리셋인지 구분할 것"),
            @ApiResponse(responseCode = "400", description = "image 누락 또는 base64 디코딩 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "세션 만료 또는 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> analyze(
            @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "`image` 는 `data:image/jpeg;base64,...` 형태의 data URL",
                    required = true)
            AnalyzeRequest request) {

        return sessions.get(request.sessionId())
                .<ResponseEntity<?>>map(session -> {
                    AnalyzeResponse response = mirror.analyze(session, request.image());
                    sessions.save(session);
                    return ResponseEntity.ok(response);
                })
                .orElseGet(MirrorController::sessionNotFound);
    }

    // --------------------------------------------------------------- 고객의 선택

    @PostMapping("/sessions/{sessionId}/assist-request")
    @Operation(summary = "직원 도움 받기", description = """
            직원 화면 대기 목록에 이 세션이 올라간다. 고객 화면에는 추천이 표시되지 않는다.
            """)
    public ResponseEntity<?> requestAssist(@PathVariable String sessionId) {
        return apply(sessionId, Session::requestAssist);
    }

    @PostMapping("/sessions/{sessionId}/self-browse")
    @Operation(summary = "혼자 볼게요", description = """
            직원 알림을 보내지 않는다. (기능명세서 3.1.1)

            자율 관람 중에도 마음이 바뀌면 다시 `assist-request` 를 호출할 수 있다.
            """)
    public ResponseEntity<?> browseAlone(@PathVariable String sessionId) {
        return apply(sessionId, Session::browseAlone);
    }

    @PostMapping("/sessions/{sessionId}/assist-cancel")
    @Operation(summary = "도움 요청 철회", description = "직원 대기 목록에서 내려간다.")
    public ResponseEntity<?> cancelAssist(@PathVariable String sessionId) {
        return apply(sessionId, Session::cancelAssist);
    }

    // ------------------------------------------------------------------ 공통 처리

    /** 상태 전이 후 갱신된 세션 상태를 돌려주는 공통 경로. */
    private ResponseEntity<?> apply(String sessionId, Consumer<Session> action) {
        return sessions.get(sessionId)
                .<ResponseEntity<?>>map(session -> {
                    action.accept(session);
                    sessions.save(session);
                    return ResponseEntity.ok(toStateResponse(session));
                })
                .orElseGet(MirrorController::sessionNotFound);
    }

    private SessionStateResponse toStateResponse(Session session) {
        boolean terminal = session.state().isTerminal();
        return new SessionStateResponse(
                session.id(),
                session.state().name(),
                session.elapsedSeconds(),
                terminal ? null : session.analysis().orElse(null),
                terminal ? null : session.track().orElse(null),
                session.isAnalysisFallback(),
                session.state() == SessionState.ASSIST_ACCEPTED,
                session.assignedStaff().map(StaffAssignment::staffName).orElse(null));
    }

    private static ResponseEntity<?> sessionNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("session_not_found",
                        "세션이 만료되었거나 존재하지 않습니다. 처음부터 다시 시작해 주세요."));
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
        // 예외 메시지에 이미지 원문이 실리지 않도록 MirrorService 에서 정제한 메시지만 사용한다.
        log.warn("bad request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("internal_error", "서버 처리 중 오류가 발생했습니다."));
    }
}
