package com.example.back.domain;

import com.example.back.dto.Lighting;
import com.example.back.dto.MoodAnalysis;
import com.example.back.dto.MusicSpec;
import com.example.back.dto.MusicTrack;
import com.example.back.dto.Outfit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 세션 상태머신 단위 테스트. Spring 컨텍스트를 띄우지 않으므로 밀리초 단위로 돈다.
 * 상태 전이 규칙은 앞으로 가장 자주 바뀔 부분이라 이 속도가 중요하다.
 */
class SessionTest {

    private static final Instant T0 = Instant.parse("2026-08-10T09:00:00Z");

    /** 테스트에서 시간을 임의로 밀 수 있는 시계. 실제 대기 없이 타임아웃을 검증한다. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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

    private MutableClock clock;

    private Session newSession() {
        clock = new MutableClock(T0);
        return Session.start("mirror-gangnam-01", "store-gangnam", "강남점 2층 A", clock);
    }

    private MoodAnalysis analysis() {
        return new MoodAnalysis(
                new Outfit(List.of("#1B1B1F", "#C8A96A"), "street-luxe", 0.6,
                        List.of("bold", "urban")),
                "calm-street",
                "Golden Hour Drift",
                new Lighting("golden-drift", "#C8A96A", "#1B1B1F", 3200, 0.7, 2500, "breathe"),
                new MusicSpec(List.of("triphop", "downtempo"), 0.4, "hiphop", "A", "minor", 88),
                "차분한 톤에 포인트 컬러가 잘 살아 있어요");
    }

    private MusicTrack track() {
        return new MusicTrack("T1", "Drift", "Artist", "https://example.com/a.mp3",
                180, "https://example.com/s", "CC BY", "jamendo", "무드와 맞음", true);
    }

    // ------------------------------------------------------------------ 기본

    @Test
    @DisplayName("세션은 IDLE 로 시작하고 ULID 형식의 id 를 갖는다")
    void startsInIdle() {
        Session session = newSession();

        assertThat(session.state()).isEqualTo(SessionState.IDLE);
        assertThat(session.id()).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(session.events()).isEmpty();
    }

    @Test
    @DisplayName("ULID 는 생성 시각 순으로 정렬된다")
    void ulidIsSortable() {
        String first = SessionId.generate(T0);
        String second = SessionId.generate(T0.plusMillis(1));

        assertThat(first).isLessThan(second);
    }

    // ------------------------------------------------------------- 정상 흐름

    @Test
    @DisplayName("직원 응대를 거치는 전체 흐름이 순서대로 진행된다")
    void happyPathWithAssist() {
        Session session = newSession();

        session.consent();
        session.startAnalysis();
        session.applyMood(analysis(), track(), false);
        session.requestAssist();
        clock.advance(Duration.ofSeconds(18));
        session.acceptAssist(new StaffAssignment("staff-042", clock.instant()));
        session.complete();

        assertThat(session.state()).isEqualTo(SessionState.ENDED);
        assertThat(session.endReason()).contains(EndReason.COMPLETED);
        assertThat(session.events()).extracting(SessionEvent::to).containsExactly(
                SessionState.CONSENTED,
                SessionState.ANALYZING,
                SessionState.MOOD_ACTIVE,
                SessionState.ASSIST_REQUESTED,
                SessionState.ASSIST_ACCEPTED,
                SessionState.ENDED);
    }

    @Test
    @DisplayName("혼자 볼게요를 선택한 세션도 정상 종료로 닫을 수 있다")
    void selfBrowsingPath() {
        Session session = newSession();

        session.consent();
        session.startAnalysis();
        session.applyMood(analysis(), track(), false);
        session.browseAlone();
        session.complete();

        assertThat(session.state()).isEqualTo(SessionState.ENDED);
        assertThat(session.endReason()).contains(EndReason.COMPLETED);
        assertThat(session.assignedStaff()).isEmpty();
    }

    @Test
    @DisplayName("정상 종료와 타임아웃은 다른 종료 사유로 남는다")
    void completedAndTimeoutAreDistinguishable() {
        Session completed = newSession();
        completed.consent();
        completed.startAnalysis();
        completed.applyMood(analysis(), track(), false);
        completed.complete();

        Session abandoned = newSession();
        abandoned.consent();
        abandoned.startAnalysis();
        abandoned.applyMood(analysis(), track(), false);
        abandoned.timeout();

        // 핵심 지표가 완주 세션 수라 이 둘이 같은 상태로 뭉치면 안 된다.
        assertThat(completed.state()).isEqualTo(SessionState.ENDED);
        assertThat(abandoned.state()).isEqualTo(SessionState.EXPIRED);
        assertThat(completed.endReason()).contains(EndReason.COMPLETED);
        assertThat(abandoned.endReason()).contains(EndReason.TIMEOUT);
    }

    @Test
    @DisplayName("자율 관람 중에도 마음이 바뀌면 직원을 부를 수 있다")
    void canRequestAssistWhileBrowsingAlone() {
        Session session = newSession();
        session.consent();
        session.startAnalysis();
        session.applyMood(analysis(), track(), false);
        session.browseAlone();

        assertThatCode(session::requestAssist).doesNotThrowAnyException();
        assertThat(session.state()).isEqualTo(SessionState.ASSIST_REQUESTED);
    }

    // -------------------------------------------------------------- 폴백 처리

    @Test
    @DisplayName("FallbackPresets 프리셋도 MOOD_ACTIVE 로 수렴하되 사유로 구분된다")
    void fallbackMoodReachesSameState() {
        Session session = newSession();
        session.consent();
        session.startAnalysis();

        session.applyMood(analysis(), null, true);

        assertThat(session.state()).isEqualTo(SessionState.MOOD_ACTIVE);
        assertThat(session.isAnalysisFallback()).isTrue();
        assertThat(session.track())
                .as("음원이 없으면 프론트가 절차적 앰비언스로 폴백한다")
                .isEmpty();
        assertThat(session.events()).last()
                .extracting(SessionEvent::reason)
                .isEqualTo(TransitionReason.MOOD_FALLBACK_APPLIED);
    }

    // ------------------------------------------------------------- 응대 잠금

    /** 도움 요청 대기 상태의 세션을 만든다. */
    private Session awaitingAssist() {
        Session session = newSession();
        session.consent();
        session.startAnalysis();
        session.applyMood(analysis(), track(), false);
        session.requestAssist();
        return session;
    }

    @Test
    @DisplayName("직원이 응대를 놓으면 다시 요청 대기로 돌아가고 담당자가 비워진다")
    void releaseReturnsToQueue() {
        Session session = awaitingAssist();
        session.acceptAssist(new StaffAssignment("staff-042", clock.instant()));

        session.releaseAssist();

        assertThat(session.state()).isEqualTo(SessionState.ASSIST_REQUESTED);
        assertThat(session.assignedStaff()).isEmpty();
    }


    @Test
    @DisplayName("같은 직원의 재요청은 멱등하게 성공한다")
    void sameStaffAcceptIsIdempotent() {
        Session session = awaitingAssist();
        session.acceptAssist(new StaffAssignment("staff-042", clock.instant()));
        int eventsBefore = session.events().size();

        session.acceptAssist(new StaffAssignment("staff-042", clock.instant()));

        assertThat(session.state()).isEqualTo(SessionState.ASSIST_ACCEPTED);
        assertThat(session.events())
                .as("버튼 중복 클릭이나 재시도로 이벤트가 늘어나면 안 된다")
                .hasSize(eventsBefore);
    }


    @Test
    @DisplayName("해제된 세션은 다른 직원이 이어받을 수 있다")
    void otherStaffCanTakeOverAfterRelease() {
        Session session = awaitingAssist();
        session.acceptAssist(new StaffAssignment("staff-042", clock.instant()));
        session.releaseAssist();

        session.acceptAssist(new StaffAssignment("staff-099", clock.instant()));

        assertThat(session.assignedStaff()).get()
                .extracting(StaffAssignment::staffId)
                .isEqualTo("staff-099");
    }


    @Test
    @DisplayName("고객이 요청을 철회하면 연출 상태로 돌아간다")
    void cancelAssistReturnsToMood() {
        Session session = newSession();
        session.consent();
        session.startAnalysis();
        session.applyMood(analysis(), track(), false);
        session.requestAssist();

        session.cancelAssist();

        assertThat(session.state()).isEqualTo(SessionState.MOOD_ACTIVE);
        assertThat(session.assistRequestedAt()).isEmpty();
    }

    // ------------------------------------------------------------- 만료 처리

    @Test
    @DisplayName("어떤 비종료 상태에서도 타임아웃으로 만료된다")
    void expiresFromAnyNonTerminalState() {
        for (SessionState state : SessionState.values()) {
            if (state.isTerminal()) {
                continue;
            }
            assertThat(state.canTransitionTo(SessionState.EXPIRED))
                    .as("%s -> EXPIRED", state)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("종료된 세션은 더 이상 전이하지 않는다")
    void terminalStatesAreFinal() {
        Session session = newSession();
        session.reset();

        assertThat(session.state()).isEqualTo(SessionState.EXPIRED);
        assertThat(session.endReason()).contains(EndReason.RESET);
        assertThatThrownBy(session::consent)
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    @DisplayName("무입력 임계 시간이 지나면 만료 대상으로 판정된다")
    void detectsIdleTimeout() {
        Session session = newSession();
        session.consent();

        assertThat(session.isIdleFor(Duration.ofMinutes(3))).isFalse();
        clock.advance(Duration.ofMinutes(3));
        assertThat(session.isIdleFor(Duration.ofMinutes(3))).isTrue();

        session.timeout();
        assertThat(session.isIdleFor(Duration.ofMinutes(3)))
                .as("이미 종료된 세션은 만료 대상이 아니다")
                .isFalse();
    }

    // ------------------------------------------------------------------ 지표

    @Test
    @DisplayName("이벤트 시각만으로 지표를 되짚을 수 있다")
    void eventTimestampsSupportMetrics() {
        Session session = newSession();
        session.consent();
        session.startAnalysis();
        session.applyMood(analysis(), track(), false);
        session.requestAssist();
        clock.advance(Duration.ofSeconds(42));
        session.acceptAssist(new StaffAssignment("staff-042", clock.instant()));

        // 도달 시간을 계산해 주는 메서드는 두지 않는다. 세션은 TTL 로 사라지지만
        // 이벤트는 남으므로, 지표는 이벤트 로그에서 뽑는 것이 맞는 자리다.
        Instant requested = eventTo(session, SessionState.ASSIST_REQUESTED).occurredAt();
        Instant accepted = eventTo(session, SessionState.ASSIST_ACCEPTED).occurredAt();

        assertThat(Duration.between(requested, accepted)).isEqualTo(Duration.ofSeconds(42));
    }

    private static SessionEvent eventTo(Session session, SessionState state) {
        return session.events().stream()
                .filter(e -> e.to() == state)
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("drainEvents 는 이벤트를 넘겨주고 버퍼를 비운다")
    void drainEventsEmptiesBuffer() {
        Session session = newSession();
        session.consent();

        List<SessionEvent> drained = session.drainEvents();

        assertThat(drained).hasSize(1);
        assertThat(session.events()).isEmpty();
    }

    @Test
    @DisplayName("세션은 이미지나 제품 정보를 보관하지 않는다")
    void keepsNeitherImageNorProduct() {
        Session session = newSession();
        session.consent();
        session.startAnalysis();
        session.applyMood(analysis(), track(), false);

        assertThat(Session.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .as("이미지와 카탈로그는 세션에 들어오지 않는다")
                .doesNotContain("image", "imageBytes", "dataUrl", "photo", "catalog");
    }

    // ------------------------------------------------------ 전이 화이트리스트

    @Nested
    @DisplayName("전이 화이트리스트 전수 검증")
    class TransitionMatrix {

        /**
         * 모든 (from, to) 조합을 실제로 시도해 enum 선언과 일치하는지 확인한다.
         * 상태나 규칙이 추가돼도 이 테스트가 자동으로 커버한다.
         */
        @Test
        @DisplayName("허용되지 않은 조합은 모두 예외를 던진다")
        void rejectsEveryDisallowedTransition() {
            for (SessionState from : SessionState.values()) {
                Set<SessionState> allowed = from.allowedNext();
                for (SessionState to : SessionState.values()) {
                    Session session = sessionInState(from);
                    if (allowed.contains(to)) {
                        assertThatCode(() -> session.transitionTo(to, TransitionReason.RESET, Map.of()))
                                .as("%s -> %s 는 허용돼야 한다", from, to)
                                .doesNotThrowAnyException();
                    } else {
                        assertThatThrownBy(() -> session.transitionTo(to, TransitionReason.RESET, Map.of()))
                                .as("%s -> %s 는 거부돼야 한다", from, to)
                                .isInstanceOf(IllegalStateTransitionException.class);
                    }
                }
            }
        }

        @Test
        @DisplayName("종료 상태에서 나가는 전이는 존재하지 않는다")
        void terminalStatesHaveNoOutgoingTransitions() {
            assertThat(SessionState.ENDED.allowedNext()).isEmpty();
            assertThat(SessionState.EXPIRED.allowedNext()).isEmpty();
        }

        @Test
        @DisplayName("직원 BE 에 통지가 필요한 상태 목록이 고정돼 있다")
        void staffVisibleStatesAreFixed() {
            Set<SessionState> visible = EnumSet.noneOf(SessionState.class);
            for (SessionState state : SessionState.values()) {
                if (state.isStaffVisible()) {
                    visible.add(state);
                }
            }
            assertThat(visible).containsExactlyInAnyOrder(
                    SessionState.ASSIST_REQUESTED,
                    SessionState.ASSIST_ACCEPTED,
                    SessionState.SELF_BROWSING,
                    SessionState.ENDED,
                    SessionState.EXPIRED);
        }

        private Session sessionInState(SessionState target) {
            Session session = newSession();
            for (SessionState step : pathTo(target)) {
                session.transitionTo(step, TransitionReason.RESET, Map.of());
            }
            return session;
        }

        private List<SessionState> pathTo(SessionState target) {
            return switch (target) {
                case IDLE -> List.of();
                case CONSENTED -> List.of(SessionState.CONSENTED);
                case ANALYZING -> List.of(SessionState.CONSENTED, SessionState.ANALYZING);
                case MOOD_ACTIVE -> List.of(SessionState.CONSENTED, SessionState.ANALYZING,
                        SessionState.MOOD_ACTIVE);
                case ASSIST_REQUESTED -> List.of(SessionState.CONSENTED, SessionState.ANALYZING,
                        SessionState.MOOD_ACTIVE, SessionState.ASSIST_REQUESTED);
                case ASSIST_ACCEPTED -> List.of(SessionState.CONSENTED, SessionState.ANALYZING,
                        SessionState.MOOD_ACTIVE, SessionState.ASSIST_REQUESTED,
                        SessionState.ASSIST_ACCEPTED);
                case SELF_BROWSING -> List.of(SessionState.CONSENTED, SessionState.ANALYZING,
                        SessionState.MOOD_ACTIVE, SessionState.SELF_BROWSING);
                case ENDED -> List.of(SessionState.CONSENTED, SessionState.ANALYZING,
                        SessionState.MOOD_ACTIVE, SessionState.ENDED);
                case EXPIRED -> List.of(SessionState.EXPIRED);
            };
        }
    }
}
