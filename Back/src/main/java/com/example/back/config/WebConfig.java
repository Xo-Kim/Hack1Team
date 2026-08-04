package com.example.back.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Vite dev 서버(5173)에서 직접 호출하는 경우를 위한 CORS.
 * vite.config.ts 의 proxy 를 쓰면 동일 출처가 되어 이 설정은 사용되지 않는다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final MoodMirrorProperties props;

    public WebConfig(MoodMirrorProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = props.cors() == null || props.cors().allowedOrigins() == null
                ? new String[]{"http://localhost:5173"}
                : props.cors().allowedOrigins().toArray(String[]::new);

        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .maxAge(3600);
    }
}
