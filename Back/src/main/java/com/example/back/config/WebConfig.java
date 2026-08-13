package com.example.back.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Vite dev 서버(5173)에서 직접 호출하는 경우를 위한 CORS.
 * vite.config.ts 의 proxy 를 쓰면 동일 출처가 되어 이 설정은 사용되지 않는다.
 * <p>
 * <b>고객 프론트 출처에는 고객용 경로만 연다.</b> 직원용 경로는 여기 등록하지 않으며,
 * 직원 화면 출처가 따로 생기면 별도 매핑으로 추가한다.
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

        // 고객 미러 화면이 쓰는 경로만 연다.
        // /api/** 로 열어두면 고객 프론트 출처에서 /api/staff/** 가 그대로 호출된다.
        // 추천을 직원 화면에만 보여준다는 원칙이 CORS 한 줄에 달려 있다.
        registry.addMapping("/api/health")
                .allowedOrigins(origins)
                .allowedMethods("GET", "OPTIONS")
                .maxAge(3600);

        registry.addMapping("/api/mirror/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .maxAge(3600);
    }
}
