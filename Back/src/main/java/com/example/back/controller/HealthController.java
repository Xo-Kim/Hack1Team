package com.example.back.controller;

import com.example.back.service.JamendoClient;
import com.example.back.service.LlmClient;
import com.example.back.service.SessionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 고객·직원 어느 쪽에도 속하지 않는 공통 진단 엔드포인트.
 * <p>
 * 컨트롤러를 둘로 나누면서 여기로 뺐다. 둘 중 한쪽에 두면 그 파트 담당자가
 * 자기 화면에 필요 없는 코드를 계속 마주치게 된다.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "0. 공통", description = "동작 모드 확인")
public class HealthController {

    private final LlmClient llm;
    private final JamendoClient jamendo;
    private final SessionStore sessions;

    public HealthController(LlmClient llm, JamendoClient jamendo, SessionStore sessions) {
        this.llm = llm;
        this.jamendo = jamendo;
        this.sessions = sessions;
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
}
