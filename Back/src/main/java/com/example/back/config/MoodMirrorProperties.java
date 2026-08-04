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

    public record Cors(List<String> allowedOrigins) {
    }

    public record Session(int ttlMinutes) {
    }
}
