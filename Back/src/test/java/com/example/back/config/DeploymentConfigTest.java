package com.example.back.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배포 환경에서만 드러나는 설정을 로컬에서 잡는다.
 * <p>
 * 이 두 가지는 코드가 멀쩡해도 배포를 실패시키는데, 로컬에서는 증상이 전혀 없어
 * 배포 로그를 보기 전까지 알 수 없다. 그래서 테스트로 고정한다.
 */
class DeploymentConfigTest {

    /**
     * PaaS 는 PORT 를 주입하고 <b>그 포트로만</b> 트래픽을 보낸다. 여기에 붙지 않으면
     * 앱은 "Started BackApplication" 까지 정상으로 찍고도 헬스체크가 계속 실패한다.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @TestPropertySource(properties = "PORT=9137")
    class PortBinding {

        @Value("${server.port}")
        int port;

        @Test
        @DisplayName("PORT 가 주어지면 그 포트에 붙는다")
        void followsInjectedPort() {
            assertThat(port).isEqualTo(9137);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    class PortFallback {

        @Value("${server.port}")
        int port;

        @Test
        @DisplayName("PORT 가 없으면 로컬 기본값 8080")
        void defaultsTo8080() {
            assertThat(port).isEqualTo(8080);
        }
    }

    /**
     * 프론트를 다른 도메인에 올리면 CORS 를 열어야 하는데, 그때마다 코드를 고치고
     * 다시 배포할 수는 없다. 환경변수 한 줄로 덮어써지는지 확인한다.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @TestPropertySource(properties =
            "CORS_ALLOWED_ORIGINS=https://mood-mirror.example.app,https://staff.example.app")
    class CorsOverride {

        @Autowired
        MoodMirrorProperties props;

        @Test
        @DisplayName("쉼표로 구분한 환경변수가 목록으로 바인딩된다")
        void bindsCommaSeparatedOrigins() {
            assertThat(props.cors().allowedOrigins())
                    .containsExactly("https://mood-mirror.example.app", "https://staff.example.app");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    class CorsDefault {

        @Autowired
        MoodMirrorProperties props;

        @Test
        @DisplayName("설정이 없으면 로컬 개발 출처가 열린다")
        void keepsLocalOrigins() {
            assertThat(props.cors().allowedOrigins())
                    .contains("http://localhost:5173", "http://localhost:5174");
        }
    }
}
