package com.example.back.controller;

import com.example.back.dto.ApiPayloads.AnalyzeRequest;
import com.example.back.dto.ApiPayloads.AnalyzeResponse;
import com.example.back.dto.ApiPayloads.ErrorResponse;
import com.example.back.dto.ApiPayloads.RecommendResponse;
import com.example.back.service.JamendoClient;
import com.example.back.service.LlmClient;
import com.example.back.service.MirrorService;
import com.example.back.service.SessionStore;
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
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "llmMode", llm.isMock() ? "mock" : "live",
                "musicMode", jamendo.isEnabled() ? "jamendo" : "synth",
                "activeSessions", sessions.size()
        );
    }

    /** 1차 — 이미지 분석. 조명·음악 스펙을 즉시 돌려준다. */
    @PostMapping("/analyze")
    public AnalyzeResponse analyze(@RequestBody AnalyzeRequest request) {
        return mirror.analyze(request.image());
    }

    /**
     * 2차 — 제품 추천.
     * 실제 서비스에서는 이 엔드포인트의 응답이 직원 단말로만 가야 한다. (PRD §1.1)
     */
    @GetMapping("/recommend/{sessionId}")
    public ResponseEntity<?> recommend(@PathVariable String sessionId) {
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
