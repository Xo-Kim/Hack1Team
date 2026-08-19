package com.example.back.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "mood-mirror")
public record MoodMirrorProperties(
        Llm llm,
        Music music,
        Cors cors,
        Session session
) {

    public record Music(Jamendo jamendo) {

        public record Jamendo(
                String clientId,
                String baseUrl,
                int timeoutSeconds
        ) {
            /** client_id 가 없으면 검색을 건너뛰고 프론트가 절차적 앰비언스로 폴백한다. */
            public boolean enabled() {
                return clientId != null && !clientId.isBlank();
            }
        }
    }

    public record Llm(
            String apiKey,
            String baseUrl,
            String visionModel,
            String textModel,
            int connectTimeoutSeconds,
            int readTimeoutSeconds
    ) {
        /** API 키가 없으면 mock 모드. 키 없이도 전체 플로우를 시연할 수 있어야 한다. */
        public boolean mock() {
            return apiKey == null || apiKey.isBlank();
        }
    }

    /**
     * @param allowedOrigins 고객 미러 화면 출처. {@code /api/health} 와 {@code /api/mirror/**} 만 열린다
     * @param staffOrigins   직원 화면 출처. <b>기본값은 비어 있고, 비어 있으면 아예 열지 않는다.</b>
     *                       추천이 나가는 유일한 통로라 고객 출처와 같은 목록에 두지 않는다 —
     *                       한 목록이면 고객 프론트 주소를 추가하는 순간 직원 경로까지 같이 열린다
     */
    public record Cors(List<String> allowedOrigins, List<String> staffOrigins) {
    }

    public record Session(int ttlMinutes) {
    }
}
