package com.example.back.service;

import com.example.back.domain.Session;
import com.example.back.domain.SessionState;
import com.example.back.dto.StaffNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 직원 단말로 상태 변화를 밀어 보낸다. Server-Sent Events 기반.
 * <p>
 * <b>WebSocket 이 아니라 SSE 인 이유</b>: 이 채널은 서버 → 직원 단방향이다. 직원의
 * 동작(응대 시작·해제)은 이미 REST 로 올라가므로 양방향이 필요 없다. SSE 는 일반 HTTP
 * 라 매장 방화벽·프록시를 그대로 통과하고, 끊기면 브라우저가 알아서 재연결한다.
 * WebSocket 을 쓰면 재연결·하트비트를 직접 구현해야 한다.
 * <p>
 * <b>폴링은 폴백으로 남아 있다.</b> {@code GET /api/staff/sessions} 가 그대로 동작하므로
 * SSE 가 막힌 환경에서도 직원 화면은 돌아간다.
 */
@Component
public class StaffNotifier {

    private static final Logger log = LoggerFactory.getLogger(StaffNotifier.class);

    /** 연결 유지 시간. 브라우저가 자동 재연결하므로 길게 잡아도 손해가 없다. */
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;

    /** 이 시간을 넘겨 미확인이면 재알림한다. (기능명세 S-HDMWGW) */
    private static final Duration REMINDER_AFTER = Duration.ofSeconds(60);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** 세션별 마지막 재알림 시각. 60초마다 한 번씩만 다시 울리게 한다. */
    private final Map<String, Instant> lastReminded = new ConcurrentHashMap<>();

    private final SessionStore sessions;
    private final Clock clock;

    public StaffNotifier(SessionStore sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    /** 직원 화면이 구독한다. 연결이 끊기면 목록에서 스스로 빠진다. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        // 구독 직후 한 번 보내 연결이 살아 있음을 알린다. 이게 없으면 첫 알림이 올 때까지
        // 클라이언트가 연결 성공 여부를 알 수 없다.
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of(
                    "waiting", sessions.findByState(SessionState.ASSIST_REQUESTED).size())));
        } catch (IOException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }

        log.debug("staff subscribed. 현재 구독 {}건", emitters.size());
        return emitter;
    }

    public int subscriberCount() {
        return emitters.size();
    }

    // --------------------------------------------------------------- 발신

    @EventListener
    public void onTransition(SessionTransitionEvent transition) {
        toNotification(transition).ifPresent(this::deliver);
    }

    /**
     * 전이를 직원용 알림으로 바꾼다. 직원이 알 필요 없는 전이(동의·분석 등)는 비운다.
     * 고객의 앞부분 진행 상황까지 밀어 보내면 대기 화면이 소음으로 가득 찬다.
     */
    private Optional<StaffNotification> toNotification(SessionTransitionEvent transition) {
        var event = transition.event();
        StaffNotification.Type type = switch (event.to()) {
            case ASSIST_REQUESTED ->
                // ASSIST_ACCEPTED 에서 돌아온 것이면 '해제', 그 외는 신규 요청이다.
                    event.from() == SessionState.ASSIST_ACCEPTED
                            ? StaffNotification.Type.ASSIST_RELEASED
                            : StaffNotification.Type.ASSIST_REQUESTED;
            case ASSIST_ACCEPTED -> StaffNotification.Type.ASSIST_ACCEPTED;
            case SELF_BROWSING -> StaffNotification.Type.SELF_BROWSING;
            case ENDED, EXPIRED -> StaffNotification.Type.SESSION_CLOSED;
            // 연출로 돌아오는 경로가 둘이라 사유로 갈라야 한다.
            // 고객이 철회한 것과 직원이 응대를 마친 것은 화면 문구가 달라야 한다.
            case MOOD_ACTIVE -> switch (event.from()) {
                case ASSIST_REQUESTED -> StaffNotification.Type.ASSIST_CANCELLED;
                case ASSIST_ACCEPTED -> StaffNotification.Type.ASSIST_FINISHED;
                default -> null;
            };
            default -> null;
        };
        if (type == null) {
            return Optional.empty();
        }
        if (type == StaffNotification.Type.SESSION_CLOSED) {
            lastReminded.remove(event.sessionId());
        }
        return Optional.of(new StaffNotification(
                type, event.sessionId(), event.mirrorId(), transition.mirrorLabel(),
                event.to().name(), 0, false, event.occurredAt()));
    }

    /**
     * 구독 중인 모든 단말로 내보낸다.
     * <p>
     * 테스트가 소켓 없이 발신 내용을 확인할 수 있도록 package-private 로 열어 둔다.
     * 실제 위험은 SSE 전송이 아니라 "어떤 전이가 어떤 알림이 되는가"와
     * "재알림이 언제 멈추는가"에 있어서, 그쪽을 검증할 수 있어야 한다.
     */
    void deliver(StaffNotification notification) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(notification.type().name().toLowerCase())
                        .data(notification));
            } catch (Exception e) {
                // 끊긴 연결이다. 콜백이 목록에서 빼주지만 즉시 정리해 다음 전송을 아낀다.
                emitters.remove(emitter);
            }
        }
        log.debug("알림 발신 {} sessionId={} 구독 {}건",
                notification.type(), notification.sessionId(), emitters.size());
    }

    // ------------------------------------------------------------- 스케줄

    /**
     * 60초 넘게 아무도 받지 않은 요청을 다시 울린다.
     * <p>
     * 재알림 자체보다 <b>멈추는 조건</b>이 중요하다. 응대가 시작되면 상태가
     * ASSIST_ACCEPTED 로 바뀌어 대상에서 빠지고, 세션이 끝나면 기록도 지운다.
     */
    @Scheduled(fixedDelayString = "${mood-mirror.notify.reminder-scan-ms:10000}")
    public void remindUnacknowledged() {
        Instant now = clock.instant();

        for (Session session : sessions.findByState(SessionState.ASSIST_REQUESTED)) {
            Instant requestedAt = session.assistRequestedAt().orElse(null);
            if (requestedAt == null || Duration.between(requestedAt, now).compareTo(REMINDER_AFTER) < 0) {
                continue;
            }
            Instant last = lastReminded.get(session.id());
            if (last != null && Duration.between(last, now).compareTo(REMINDER_AFTER) < 0) {
                continue;
            }
            lastReminded.put(session.id(), now);

            deliver(new StaffNotification(
                    StaffNotification.Type.ASSIST_REQUESTED,
                    session.id(), session.mirrorId(), session.mirrorLabel(),
                    session.state().name(),
                    Duration.between(requestedAt, now).toSeconds(),
                    true, now));

            log.info("미확인 요청 재알림 sessionId={} 대기 {}초",
                    session.id(), Duration.between(requestedAt, now).toSeconds());
        }
    }

    /**
     * 하트비트. 중간 프록시가 유휴 연결을 끊는 것을 막는다.
     * <p>
     * 주석 형태로 보내므로 클라이언트의 이벤트 핸들러에는 잡히지 않는다.
     * 죽은 연결을 실제로 걷어내는 것도 이 주기다 — 전송을 시도해야 끊긴 걸 알 수 있다.
     */
    @Scheduled(fixedDelayString = "${mood-mirror.notify.heartbeat-ms:15000}")
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }
}
