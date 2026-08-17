package com.example.back.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기 작업 활성화.
 * <p>
 * {@code SessionReaper}(만료 세션 정리)와 {@code StaffNotifier}(재알림·하트비트)가
 * 이 설정에 걸려 있다. 별도 클래스로 둔 이유는 테스트에서 스케줄러만 끄고 싶을 때
 * 이 설정 하나만 제외하면 되기 때문이다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
