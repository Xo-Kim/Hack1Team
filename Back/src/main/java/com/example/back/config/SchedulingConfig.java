package com.example.back.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기 작업과 비동기 실행 활성화.
 * <p>
 * {@code SessionReaper}(만료 세션 정리)와 {@code StaffNotifier}(재알림·하트비트)가
 * 스케줄링에 걸려 있고, {@code StaffService} 의 추천 예열이 비동기에 걸려 있다.
 * 별도 클래스로 둔 이유는 테스트에서 이것만 제외하면 되기 때문이다.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {
}
