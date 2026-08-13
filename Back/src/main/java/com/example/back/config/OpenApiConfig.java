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
                        .version("v0.6")
                        .description("""
                                착장 사진을 분석해 조명·음향 연출과 직원용 제품 추천을 만드는 API.

                                ## 경로가 둘로 나뉘어 있다
                                | 경로 | 화면 | 추천 노출 |
                                |---|---|---|
                                | `/api/mirror/**` | 고객 미러 디스플레이 | **없음** |
                                | `/api/staff/**` | 직원 태블릿·모바일 | 있음 |

                                고객 화면에 AI 추천을 노출하지 않는 것이 이 서비스의 차별점이라,
                                고객이 도달 가능한 경로에는 추천을 돌려주는 엔드포인트가 아예 없다.
                                CORS 도 고객 프론트 출처에는 `/api/mirror/**` 만 열려 있다.

                                ## 고객 호출 순서
                                1. `POST /api/mirror/sessions` — 세션을 연다. `mirrorId`·`storeId` 필수
                                2. `POST /api/mirror/sessions/{id}/consent` — 촬영·분석 동의
                                3. `POST /api/mirror/sessions/{id}/analyze` — 조명·음향 스펙과 음원 수신 (5~7초)
                                4. `POST .../assist-request` 또는 `.../self-browse` — 고객의 응대 선택
                                5. `GET /api/mirror/sessions/{id}` — 폴링. `musicDucked` 로 볼륨 하향 판단

                                ## 직원 호출 순서
                                1. `GET /api/staff/sessions` — 대기 목록 폴링
                                2. `GET /api/staff/sessions/{id}` — 응대 카드 (무드·팔레트·추천)
                                3. `POST /api/staff/sessions/{id}/accept` — 응대 점유. 중복이면 409

                                ## 폴백
                                LLM 이나 음원 검색이 실패해도 오류를 내지 않는다.
                                사전 정의 프리셋과 프리필터 점수로 대체하고 `fallback: true` 로 알린다.
                                `GET /api/health` 의 `llmMode` / `musicMode` 로 현재 모드를 확인할 수 있다.

                                ## 프라이버시
                                전송된 이미지는 메모리에서만 처리되고 저장되지 않는다.
                                세션에도 분석 결과 텍스트만 남으며, 직원 화면으로도 텍스트만 나간다.
                                """))
                .servers(List.of(new Server()
                        .url("http://localhost:" + port)
                        .description("로컬 개발 서버")));
    }
}
