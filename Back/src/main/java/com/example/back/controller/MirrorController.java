package com.example.back.controller;

import com.example.back.dto.ApiPayloads.AnalyzeRequest;
import com.example.back.dto.ApiPayloads.AnalyzeResponse;
import com.example.back.dto.ApiPayloads.ErrorResponse;
import com.example.back.dto.ApiPayloads.RecommendResponse;
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

@RestController
@RequestMapping("/api")
@Tag(name = "Mood Mirror", description = "촬영 → 분석 → 조명·음향·추천 파이프라인")
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

    @PostMapping("/analyze")
    @Operation(
            summary = "1차 — 착장 분석 + 조명·음향 생성",
            description = """
                    촬영 이미지를 받아 조명 스펙·음향 스펙·재생할 음원을 돌려준다.

                    내부적으로 LLM 을 2회 호출한다 (Vision 분석 → 후보 곡 중 선곡).
                    응답 시간은 실측 5~7초.

                    **이미지는 저장되지 않는다.** 메모리에서만 처리하고 폐기하며,
                    `sessionId` 로 보관되는 것은 분석 결과 텍스트뿐이다.

                    LLM 이 실패해도 200 을 돌려준다. 이때 `fallback: true` 이고
                    사전 정의 프리셋 5종 중 하나가 적용된다 (이미지 해시 기준이라 같은 사진은 같은 결과).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "분석 성공. `fallback` 값으로 AI 결과인지 프리셋인지 구분할 것"),
            @ApiResponse(responseCode = "400", description = "image 누락 또는 base64 디코딩 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public AnalyzeResponse analyze(
            @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "`image` 는 `data:image/jpeg;base64,...` 형태의 data URL",
                    required = true)
            AnalyzeRequest request) {
        return mirror.analyze(request.image());
    }

    @GetMapping("/recommend/{sessionId}")
    @Operation(
            summary = "2차 — 제품 추천 (직원 전용)",
            description = """
                    카테고리별(가방·지갑·벨트) 3종씩, 순위와 추천 이유를 돌려준다.

                    **실제 서비스에서는 이 응답이 직원 단말로만 가야 한다.**
                    고객 화면에 AI 추천을 노출하지 않는 것이 이 서비스의 설계 원칙이다 (PRD §1.1).

                    LLM 랭킹이 실패하면 프리필터 점수 상위 3종으로 대체하고 `fallback: true` 로 알린다.
                    선정된 `productId` 는 카탈로그와 대조되므로 존재하지 않는 제품은 절대 나오지 않는다.
                    """)
    @ApiResponses({
            // ResponseEntity<?> 는 와일드카드라 springdoc 이 타입을 못 잡는다.
            // 명시하지 않으면 RecommendResponse 이하 스키마가 문서에서 통째로 빠진다.
            @ApiResponse(responseCode = "200", description = "추천 성공",
                    content = @Content(schema = @Schema(implementation = RecommendResponse.class))),
            @ApiResponse(responseCode = "404", description = "세션 만료 또는 없음 (기본 TTL 15분)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> recommend(
            @Parameter(description = "`/api/analyze` 응답의 sessionId", required = true)
            @PathVariable String sessionId) {
        return mirror.recommend(sessionId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("session_not_found",
                                "세션이 만료되었거나 존재하지 않습니다. 다시 촬영해 주세요.")));
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
