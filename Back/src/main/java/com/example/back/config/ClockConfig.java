package com.example.back.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 시각의 단일 출처.
 * <p>
 * {@code Instant.now()} 를 직접 부르면 타임아웃이나 직원 도달 시간을 테스트할 때
 * 실제로 기다려야 한다. Clock 을 주입하면 테스트가 시간을 임의로 밀 수 있다.
 * 모든 시각은 UTC 로 다루고, 표시 시점에만 지역 시간으로 바꾼다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
