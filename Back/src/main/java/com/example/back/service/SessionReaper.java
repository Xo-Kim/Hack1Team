package com.example.back.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 방치된 세션을 주기적으로 걷어낸다.
 * <p>
 * 이전에는 세션 생성 시점과 직원 목록 조회 시점에 얹혀 있었다. 그래서 매장이 한산하면
 * 다음 손님이 올 때까지, 또는 직원이 화면을 열 때까지 <b>떠난 고객의 도움 요청이 대기
 * 목록에 계속 남았다.</b> 아무도 없는 미러로 직원이 찾아가게 되는 상황이다.
 * <p>
 * 조회가 상태를 바꾸지 않도록 정리 책임을 이 컴포넌트로 분리했다.
 */
@Component
public class SessionReaper {

    private static final Logger log = LoggerFactory.getLogger(SessionReaper.class);

    private final SessionStore sessions;

    public SessionReaper(SessionStore sessions) {
        this.sessions = sessions;
    }

    /**
     * 무입력 타임아웃은 분 단위라 초 단위로 훑을 이유가 없다.
     * 다만 주기가 너무 길면 미응대 알림이 그만큼 늦게 취소된다.
     */
    @Scheduled(fixedDelayString = "${mood-mirror.session.reap-interval-ms:20000}")
    public void reap() {
        int evicted = sessions.evictIdle();
        if (evicted > 0) {
            log.info("만료 세션 {}건 정리. 남은 세션 {}건", evicted, sessions.size());
        }
    }
}
