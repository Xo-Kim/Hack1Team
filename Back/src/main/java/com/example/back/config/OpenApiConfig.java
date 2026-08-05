package com.example.back.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private int port;

    @Bean
    public OpenAPI moodMirrorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MCM Mood Mirror API")
                        .version("v0.5")
                        .description("""
                                착장 사진을 분석해 조명·음향·제품 추천을 만드는 API.

                                ## 호출 순서
                                1. `POST /api/analyze` — 촬영 이미지를 보내 조명·음향 스펙과 음원을 받는다.
                                   응답의 `sessionId` 를 다음 호출에 쓴다.
                                2. `GET /api/recommend/{sessionId}` — 제품 추천을 받는다.
                                   연출이 시작된 뒤 백그라운드로 호출해 지연을 감춘다.

                                ## 폴백
                                LLM 이나 음원 검색이 실패해도 오류를 내지 않는다.
                                사전 정의 프리셋과 프리필터 점수로 대체하고 `fallback: true` 로 알린다.
                                `GET /api/health` 의 `llmMode` / `musicMode` 로 현재 모드를 확인할 수 있다.

                                ## 프라이버시
                                전송된 이미지는 메모리에서만 처리되고 저장되지 않는다.
                                세션에도 분석 결과 텍스트만 남는다.
                                """))
                .servers(List.of(new Server()
                        .url("http://localhost:" + port)
                        .description("로컬 개발 서버")));
    }
}
