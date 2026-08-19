package com.example.back.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CORS. 프론트를 백엔드와 다른 출처에 배포할 때만 쓰인다.
 * <p>
 * 로컬에서는 Vite proxy 가, 프론트를 같은 서버에서 서빙하면 동일 출처가 받아주므로
 * 둘 다 이 설정을 타지 않는다.
 * <p>
 * <b>고객 경로와 직원 경로를 다른 목록으로 연다.</b> 직원 경로는 제품 추천이 시스템
 * 밖으로 나가는 유일한 통로라, 한 목록으로 묶으면 고객 프론트 주소를 추가하는 순간
 * 직원 경로까지 같이 열린다. 그래서 직원 목록은 기본값이 비어 있고, 비어 있으면
 * 매핑 자체를 등록하지 않는다 — 설정을 깜빡한 상태의 기본값이 "닫힘"이어야 한다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private static final String[] LOCAL_DEV = {"http://localhost:5173"};

    private final MoodMirrorProperties props;

    public WebConfig(MoodMirrorProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] mirrorOrigins = origins(
                props.cors() == null ? null : props.cors().allowedOrigins(), LOCAL_DEV);

        // 고객 미러 화면이 쓰는 경로만 연다.
        // /api/** 로 열어두면 고객 프론트 출처에서 /api/staff/** 가 그대로 호출된다.
        registry.addMapping("/api/health")
                .allowedOrigins(mirrorOrigins)
                .allowedMethods("GET", "OPTIONS")
                .maxAge(3600);

        registry.addMapping("/api/mirror/**")
                .allowedOrigins(mirrorOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .maxAge(3600);

        String[] staffOrigins = origins(
                props.cors() == null ? null : props.cors().staffOrigins(), new String[0]);

        if (staffOrigins.length == 0) {
            // 같은 출처에서 서빙하는 배치라면 이게 정상이다. 다른 도메인에 올렸다면
            // 직원 화면이 조용히 먹통이 되므로 기동 로그에 남긴다.
            log.info("직원 경로 CORS 미개방 — 직원 화면은 백엔드와 동일 출처에서만 동작합니다. "
                    + "다른 도메인에 올렸다면 CORS_STAFF_ORIGINS 를 설정하세요.");
            return;
        }

        log.info("직원 경로 CORS 개방: {}", List.of(staffOrigins));
        registry.addMapping("/api/staff/**")
                .allowedOrigins(staffOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .maxAge(3600);
    }

    /** 빈 문자열 항목을 걸러낸다. 환경변수를 빈 값으로 두면 [""] 로 바인딩된다. */
    private static String[] origins(List<String> configured, String[] fallback) {
        if (configured == null) {
            return fallback;
        }
        String[] cleaned = configured.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .toArray(String[]::new);
        return cleaned.length == 0 ? fallback : cleaned;
    }
}
