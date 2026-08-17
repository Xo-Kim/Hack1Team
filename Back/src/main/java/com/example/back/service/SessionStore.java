package com.example.back.service;

import com.example.back.config.MoodMirrorProperties;
import com.example.back.domain.Session;
import com.example.back.domain.SessionEvent;
import com.example.back.domain.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher publisher;

    public SessionStore(MoodMirrorProperties props, Clock clock,
                        ApplicationEventPublisher publisher) {
        int minutes = props.session() == null ? 15 : props.session().ttlMinutes();
        this.idleTimeout = Duration.ofMinutes(minutes);
        this.clock = clock;
        this.publisher = publisher;
    }

    /** 미러 대기 화면에서 세션을 연다. */
    public Session start(String mirrorId, String storeId, String mirrorLabel) {
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
     * 조회는 상태를 바꾸지 않는다. 만료 정리는 {@link SessionReaper} 가 주기적으로 맡는다.
     */
    public List<Session> findStaffVisible() {
        return sessions.values().stream()
                // isStaffVisible() 은 종료 상태도 true 로 본다. 직원 BE 에 "이 세션은 끝났으니
                // 알림을 내려라"를 통지해야 하기 때문인데, 그건 푸시의 사정이지 목록의 사정이
                // 아니다. 떠난 고객을 대기 목록에 남기면 직원이 없는 손님을 찾아간다.
                .filter(s -> !s.state().isTerminal() && s.state().isStaffVisible())
                .toList();
    }

    /**
     * 전이 이벤트를 회수해 로그에 적재하고 알림으로 내보낸다.
     * <p>
     * 지금은 메모리에 쌓지만 Postgres 로 옮길 자리다. 세션은 사라져도 이벤트는 남아야
     * 무드 연출 완료율·완주율·직원 도달 시간을 나중에 계산할 수 있다.
     * <p>
     * 알림 발행이 여기 있는 이유는 <b>빠지는 지점을 없애기 위해서</b>다. 서비스마다
     * 알림을 부르게 하면 새 전이를 추가한 사람이 반드시 한 번은 잊는다. 모든 전이는
     * 결국 이 메서드를 지나므로, 여기 한 곳에 두면 누락이 구조적으로 불가능해진다.
     */
    public void save(Session session) {
        sessions.put(session.id(), session);

        List<SessionEvent> drained = session.drainEvents();
        eventLog.addAll(drained);
        publish(drained, session.mirrorLabel());
    }

    private void publish(List<SessionEvent> events, String mirrorLabel) {
        for (SessionEvent event : events) {
            publisher.publishEvent(new SessionTransitionEvent(event, mirrorLabel));
        }
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
     * 무입력으로 방치된 세션과 이미 끝난 세션을 걷어낸다.
     * {@link SessionReaper} 가 주기적으로 호출한다.
     *
     * @return 걷어낸 건수
     */
    public int evictIdle() {
        List<Session> targets = sessions.values().stream()
                // 종료 상태도 같이 걷는다. reset()/timeout() 직후의 save() 는 EXPIRED 세션을
                // 맵에 도로 넣는데, isIdleFor() 는 종료 상태에서 항상 false 라 그것만으로는
                // 영원히 안 걸린다.
                .filter(s -> s.state().isTerminal() || s.isIdleFor(idleTimeout))
                .toList();
        targets.forEach(this::expire);
        return targets.size();
    }

    private void expire(Session session) {
        if (!session.state().isTerminal()) {
            session.timeout();
            log.info("session expired sessionId={} mirrorId={}", session.id(), session.mirrorId());
        }
        // 만료도 전이다. 알림을 내려야 직원 대기 목록에서 사라진다.
        List<SessionEvent> drained = session.drainEvents();
        eventLog.addAll(drained);
        publish(drained, session.mirrorLabel());

        sessions.remove(session.id());
    }
}
