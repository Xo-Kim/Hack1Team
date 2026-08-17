package com.example.back.service;

import com.example.back.config.MoodMirrorProperties;
import com.example.back.domain.Session;
import com.example.back.domain.StaffAssignment;
import com.example.back.dto.Lighting;
import com.example.back.dto.MoodAnalysis;
import com.example.back.dto.MusicSpec;
import com.example.back.dto.Outfit;
import com.example.back.dto.StaffNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 발신 로직 검증.
 * <p>
 * SSE 전송 자체(소켓 쓰기)는 검증하지 않는다. 여기서 확인하는 것은 <b>어떤 전이가
 * 어떤 알림이 되는가</b>와 <b>재알림이 언제 멈추는가</b>다. 재알림은 멈추는 조건이
 * 틀리면 직원 단말이 소음으로 가득 차므로 실제 위험은 그쪽에 있다.
 */
class StaffNotifierTest {

    private MutableClock clock;
    private SessionStore store;
    private StaffNotifier notifier;
    private List<StaffNotification> sent;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-08-17T10:00:00Z"));
        sent = new ArrayList<>();

        // 저장소가 발행한 전이를 그대로 notifier 로 넘긴다. 스프링 컨텍스트 없이
        // 이벤트 배선만 흉내 내면 되므로 발행자를 람다로 준다.
        store = new SessionStore(props(), clock, event -> {
            if (event instanceof SessionTransitionEvent transition) {
                notifier.onTransition(transition);
            }
        });
        notifier = new StaffNotifier(store, clock) {
            @Override
            void deliver(StaffNotification notification) {
                sent.add(notification);
            }
        };
    }

    // ------------------------------------------------------------- 전이 → 알림

    @Test
    @DisplayName("도움 요청은 ASSIST_REQUESTED 알림이 된다")
    void assistRequestBecomesNotification() {
        Session session = moodActive();
        session.requestAssist();
        store.save(session);

        assertThat(sent).extracting(StaffNotification::type)
                .containsExactly(StaffNotification.Type.ASSIST_REQUESTED);
        assertThat(sent.get(0).mirrorLabel()).isEqualTo("2F 피팅룸 A");
        assertThat(sent.get(0).reminder()).isFalse();
    }

    @Test
    @DisplayName("동의·분석 같은 앞단 전이는 알림을 만들지 않는다")
    void earlyTransitionsAreSilent() {
        Session session = store.start("mirror-01", "store-01", "2F 피팅룸 A");
        session.consent();
        session.startAnalysis();
        session.applyMood(analysis(), null, true);
        store.save(session);

        // 직원 대기 화면이 고객의 진행 상황으로 가득 차면 안 된다.
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("응대 해제는 신규 요청이 아니라 ASSIST_RELEASED 로 구분된다")
    void releaseIsDistinguishedFromNewRequest() {
        Session session = moodActive();
        session.requestAssist();
        session.acceptAssist(new StaffAssignment("staff-01", "김직원", clock.instant()));
        store.save(session);
        sent.clear();

        session.releaseAssist();
        store.save(session);

        // 둘 다 ASSIST_REQUESTED 상태로 가지만 직원 화면의 문구가 달라야 한다.
        assertThat(sent).extracting(StaffNotification::type)
                .containsExactly(StaffNotification.Type.ASSIST_RELEASED);
    }

    @Test
    @DisplayName("요청 철회는 ASSIST_CANCELLED, 그냥 연출로 돌아간 것과 구분된다")
    void cancelBecomesCancelledNotification() {
        Session session = moodActive();
        session.requestAssist();
        store.save(session);
        sent.clear();

        session.cancelAssist();
        store.save(session);

        assertThat(sent).extracting(StaffNotification::type)
                .containsExactly(StaffNotification.Type.ASSIST_CANCELLED);
    }

    @Test
    @DisplayName("세션이 끝나면 알림을 내리라고 통지한다")
    void terminalStatesNotifyClosure() {
        Session session = moodActive();
        session.requestAssist();
        store.save(session);
        sent.clear();

        session.reset();
        store.save(session);

        assertThat(sent).extracting(StaffNotification::type)
                .containsExactly(StaffNotification.Type.SESSION_CLOSED);
    }

    // --------------------------------------------------------------- 재알림

    @Test
    @DisplayName("60초를 넘겨야 재알림한다")
    void remindsOnlyAfterThreshold() {
        Session session = moodActive();
        session.requestAssist();
        store.save(session);
        sent.clear();

        clock.advance(Duration.ofSeconds(59));
        notifier.remindUnacknowledged();
        assertThat(sent).isEmpty();

        clock.advance(Duration.ofSeconds(2));
        notifier.remindUnacknowledged();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).reminder()).isTrue();
        assertThat(sent.get(0).waitingSeconds()).isEqualTo(61);
    }

    @Test
    @DisplayName("재알림은 60초에 한 번씩만 — 스캔 주기마다 울리지 않는다")
    void reminderIsThrottled() {
        Session session = moodActive();
        session.requestAssist();
        store.save(session);
        sent.clear();

        clock.advance(Duration.ofSeconds(61));
        notifier.remindUnacknowledged();
        notifier.remindUnacknowledged();
        notifier.remindUnacknowledged();

        // 스캔은 10초마다 돈다. 그때마다 울리면 직원 단말이 소음이 된다.
        assertThat(sent).hasSize(1);

        clock.advance(Duration.ofSeconds(61));
        notifier.remindUnacknowledged();
        assertThat(sent).hasSize(2);
    }

    @Test
    @DisplayName("응대가 시작되면 재알림이 멈춘다")
    void reminderStopsOnceAccepted() {
        Session session = moodActive();
        session.requestAssist();
        store.save(session);

        clock.advance(Duration.ofSeconds(90));
        session.acceptAssist(new StaffAssignment("staff-01", "김직원", clock.instant()));
        store.save(session);
        sent.clear();

        clock.advance(Duration.ofSeconds(120));
        notifier.remindUnacknowledged();

        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("세션이 사라지면 재알림도 멈춘다")
    void reminderStopsWhenSessionGone() {
        Session session = moodActive();
        session.requestAssist();
        store.save(session);

        session.reset();
        store.save(session);
        store.evictIdle();
        sent.clear();

        clock.advance(Duration.ofSeconds(300));
        notifier.remindUnacknowledged();

        assertThat(sent).isEmpty();
    }

    // ------------------------------------------------------------------ 헬퍼

    private Session moodActive() {
        Session session = store.start("mirror-01", "store-01", "2F 피팅룸 A");
        session.consent();
        session.startAnalysis();
        session.applyMood(analysis(), null, true);
        store.save(session);
        sent.clear();
        return session;
    }

    private static MoodAnalysis analysis() {
        return new MoodAnalysis(
                new Outfit(List.of("black"), "street", 0.5, List.of("confident")),
                "confident-night", "Munich Midnight",
                new Lighting("Munich Midnight", "#1a1a2e", "#c8873c", 2700, 0.55, 2500, "breathe"),
                new MusicSpec(List.of("boombap"), 0.6, "hiphop", "C", "minor", 90),
                null);
    }

    private static MoodMirrorProperties props() {
        return new MoodMirrorProperties(null, null, null, new MoodMirrorProperties.Session(15));
    }

    /** 테스트에서 시간을 임의로 미는 시계. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
