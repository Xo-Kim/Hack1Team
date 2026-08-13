package com.example.back.service;

import com.example.back.config.MoodMirrorProperties;
import com.example.back.domain.Session;
import com.example.back.domain.SessionEvent;
import com.example.back.domain.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 인메모리 세션 저장소. 기존 {@code Entry} 대신 {@link Session} 애그리게이트를 담는다.
 * <p>
 * <b>이미지는 여기에 들어오지 않는다.</b> 분석 결과 텍스트만 보관한다.
 * <p>
 * 만료 판정이 {@code createdAt + TTL} 에서 {@link Session#isIdleFor(Duration)} 으로 바뀌었다.
 * 생성 시각 기준으로 자르면 응대가 길어진 세션이 대화 도중에 사라진다. 무입력 기준이면
 * 고객이 화면을 쓰는 동안에는 살아 있고, 방치된 세션만 정리된다.
 * <p>
 * Redis 로 옮길 때를 대비해 조회·저장을 메서드로만 노출한다. 지금은 프로세스가 하나라
 * ConcurrentHashMap 으로 충분하다.
 */
@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /*
     * sessions 는 ConcurrentHashMap 인데 이벤트 로그만 평범한 ArrayList 면
     * 동시 요청에서 깨진다. save() 는 요청 스레드에서 호출되고 evictIdle() 도
     * 같이 도는데, ArrayList 는 동시 addAll 을 견디지 못한다.
     * 미러가 여러 대면 바로 재현되므로 동시 쓰기가 안전한 구현을 쓴다.
     *
     * 이벤트는 세션당 몇 건이라 쓰기가 잦지 않고, 조회(events())는 지표 산출 때만
     * 일어나므로 읽기 최적화된 CopyOnWriteArrayList 가 이 사용 패턴에 맞는다.
     */
    private final List<SessionEvent> eventLog = new CopyOnWriteArrayList<>();

    private final Duration idleTimeout;
    private final Clock clock;

    public SessionStore(MoodMirrorProperties props, Clock clock) {
        int minutes = props.session() == null ? 15 : props.session().ttlMinutes();
        this.idleTimeout = Duration.ofMinutes(minutes);
        this.clock = clock;
    }

    /** 미러 대기 화면에서 세션을 연다. */
    public Session start(String mirrorId, String storeId, String mirrorLabel) {
        evictIdle();
        Session session = Session.start(mirrorId, storeId, mirrorLabel, clock);
        sessions.put(session.id(), session);
        return session;
    }

    public Optional<Session> get(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.state().isTerminal() || session.isIdleFor(idleTimeout)) {
            expire(session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /** 상태별 조회. */
    public List<Session> findByState(SessionState state) {
        return sessions.values().stream()
                .filter(s -> s.state() == state)
                .toList();
    }

    /**
     * 직원 화면 목록에 올라가야 할 세션들. 판정 기준은 {@link SessionState#isStaffVisible()} 이다.
     * <p>
     * 훑기 전에 {@link #evictIdle()} 을 한 번 돌린다. 원래 만료 정리는 세션 생성 시점에만
     * 일어나는데, 매장이 한산하면 다음 손님이 올 때까지 아무도 그 경로를 밟지 않아
     * 떠난 고객의 도움 요청이 대기 목록에 계속 남는다. 직원 화면은 몇 초 간격으로
     * 폴링하므로 지금은 이 조회가 사실상의 청소부 역할을 한다.
     * <p>
     * 스케줄러가 생기면 이 호출은 빼야 한다. 조회가 상태를 바꾸는 것은 임시방편이다.
     */
    public List<Session> findStaffVisible() {
        evictIdle();
        return sessions.values().stream()
                // isStaffVisible() 은 종료 상태도 true 로 본다. 직원 BE 에 "이 세션은 끝났으니
                // 알림을 내려라"를 통지해야 하기 때문인데, 그건 푸시의 사정이지 목록의 사정이
                // 아니다. 떠난 고객을 대기 목록에 남기면 직원이 없는 손님을 찾아간다.
                .filter(s -> !s.state().isTerminal() && s.state().isStaffVisible())
                .toList();
    }

    /**
     * 전이 이벤트를 회수해 로그에 적재한다.
     * <p>
     * 지금은 메모리에 쌓지만 Postgres 로 옮길 자리다. 세션은 사라져도 이벤트는 남아야
     * 무드 연출 완료율·촬영 실행률·직원 도달 시간을 나중에 계산할 수 있다.
     */
    public void save(Session session) {
        sessions.put(session.id(), session);
        eventLog.addAll(session.drainEvents());
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public int size() {
        return sessions.size();
    }

    /** 지표 산출용. 실제 서비스에서는 테이블 조회로 대체된다. */
    public List<SessionEvent> events() {
        return List.copyOf(eventLog);
    }

    /**
     * 무입력으로 방치된 세션을 만료시킨다.
     * <p>
     * 지금은 세션 생성 시점에만 훑지만, 미응대 알림을 제때 취소하려면 스케줄러가 필요하다.
     * 고객이 자리를 떠도 다음 손님이 오기 전까지는 아무도 이 메서드를 호출하지 않기 때문이다.
     */
    public void evictIdle() {
        sessions.values().stream()
                // 종료 상태도 같이 걷는다. reset()/timeout() 직후의 save() 는 EXPIRED 세션을
                // 맵에 도로 넣는데, isIdleFor() 는 종료 상태에서 항상 false 라 그것만으로는
                // 영원히 안 걸린다. 지금까지는 누군가 get() 으로 그 id 를 조회해야만 사라졌다.
                .filter(s -> s.state().isTerminal() || s.isIdleFor(idleTimeout))
                .toList()
                .forEach(this::expire);
    }

    private void expire(Session session) {
        if (!session.state().isTerminal()) {
            session.timeout();
            log.info("session expired sessionId={} mirrorId={}", session.id(), session.mirrorId());
        }
        eventLog.addAll(session.drainEvents());
        sessions.remove(session.id());
    }
}
