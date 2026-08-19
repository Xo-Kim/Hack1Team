package com.example.back.domain;

import com.example.back.dto.MoodAnalysis;
import com.example.back.dto.MusicTrack;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 미러 세션 애그리게이트. Spring 의존성이 없는 순수 도메인 객체다.
 * <p>
 * 프로토타입의 {@code SessionStore.Entry}(analysis + fallback + createdAt)를
 * 상태와 이벤트까지 갖는 객체로 확장한 것이다. SessionStore 는 이 객체를
 * 담는 저장소로 바뀌고, 만료 판정은 {@link #isIdleFor(Duration)} 이 맡는다.
 * <p>
 * 상태 전이는 반드시 이 클래스의 도메인 메서드를 통해서만 일어나며,
 * 전이할 때마다 {@link SessionEvent} 가 자동으로 적재된다. 이벤트 기록을
 * 서비스 계층에 맡기면 반드시 빠뜨리는 지점이 생기므로 한 곳에 묶었다.
 * <p>
 * <b>이미지는 이 객체에 들어오지 않는다.</b> {@link #applyMood} 는 분석 결과만 받는다.
 * <b>제품 정보 필드도 없다.</b> 추천은 직원 BE 소관이므로 고객 BE 에는 노출할 경로 자체가 없다.
 * <p>
 * 스레드 안전하지 않다. 세션당 직렬 처리를 전제로 하며 동시 요청은 저장소에서 막는다.
 */
public class Session {

    private final String id;
    private final String mirrorId;
    private final String storeId;
    private final String mirrorLabel;
    private final Instant startedAt;
    private final Clock clock;
    private final List<SessionEvent> events = new ArrayList<>();

    private SessionState state;
    private Instant lastActivityAt;
    private MoodAnalysis analysis;
    private MusicTrack track;
    private boolean analysisFallback;
    private StaffAssignment assignedStaff;
    private Instant assistRequestedAt;
    private EndReason endReason;

    private Session(String id, String mirrorId, String storeId, String mirrorLabel, Clock clock) {
        this.id = Objects.requireNonNull(id, "id");
        this.mirrorId = Objects.requireNonNull(mirrorId, "mirrorId");
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.mirrorLabel = mirrorLabel;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.state = SessionState.IDLE;
        this.startedAt = clock.instant();
        this.lastActivityAt = this.startedAt;
    }

    /**
     * 미러 대기 화면에서 세션을 연다.
     * <p>
     * 프로토타입은 {@code /api/analyze} 응답에서 sessionId 를 처음 발급하지만,
     * 직원 BE 연동에는 분석 이전부터 mirrorId·storeId 가 필요하다.
     * 그래서 세션 생성을 분석보다 앞으로 당겼다.
     */
    public static Session start(String mirrorId, String storeId, String mirrorLabel, Clock clock) {
        return new Session(SessionId.generate(clock.instant()), mirrorId, storeId, mirrorLabel, clock);
    }

    /** 저장소에서 복원할 때 사용. 이벤트는 이미 적재된 것으로 보고 다시 쌓지 않는다. */
    public static Session rehydrate(String id, String mirrorId, String storeId, String mirrorLabel,
                                    SessionState state, Instant lastActivityAt,
                                    MoodAnalysis analysis, MusicTrack track, boolean analysisFallback,
                                    StaffAssignment assignedStaff, Instant assistRequestedAt,
                                    Clock clock) {
        Session session = new Session(id, mirrorId, storeId, mirrorLabel, clock);
        session.state = Objects.requireNonNull(state, "state");
        if (lastActivityAt != null) {
            session.lastActivityAt = lastActivityAt;
        }
        session.analysis = analysis;
        session.track = track;
        session.analysisFallback = analysisFallback;
        session.assignedStaff = assignedStaff;
        session.assistRequestedAt = assistRequestedAt;
        return session;
    }

    // ---------------------------------------------------------------- 고객 동작

    /** 촬영·분석 동의. 프론트의 카운트다운은 이 이후 클라이언트에서만 진행된다. */
    public void consent() {
        transitionTo(SessionState.CONSENTED, TransitionReason.CONSENT_GIVEN, Map.of());
    }

    /** 분석용 스틸컷 수신. base64 이미지는 도메인에 들어오지 않는다. */
    public void startAnalysis() {
        transitionTo(SessionState.ANALYZING, TransitionReason.ANALYSIS_STARTED, Map.of());
    }

    /**
     * 무드 연출 적용. LLM 분석 성공과 FallbackPresets 폴백 모두 MOOD_ACTIVE 로 수렴한다.
     * 고객 경험은 동일해야 하고, 구분은 이벤트 사유와 {@link #isAnalysisFallback()} 로만 남긴다.
     *
     * @param track 재생할 음원. null 이면 프론트가 절차적 앰비언스로 폴백한다
     */
    public void applyMood(MoodAnalysis analysis, MusicTrack track, boolean fallback) {
        Objects.requireNonNull(analysis, "analysis");
        this.analysis = analysis;
        this.track = track;
        this.analysisFallback = fallback;

        Map<String, String> metadata = new HashMap<>();
        metadata.put("conceptName", String.valueOf(analysis.conceptName()));
        metadata.put("mood", String.valueOf(analysis.mood()));
        if (track != null) {
            metadata.put("trackId", String.valueOf(track.id()));
        }
        transitionTo(SessionState.MOOD_ACTIVE,
                fallback ? TransitionReason.MOOD_FALLBACK_APPLIED : TransitionReason.MOOD_APPLIED,
                metadata);
    }

    /** 직원 도움 받기 선택. 실제 알림 전송은 서비스 계층이 담당한다. */
    public void requestAssist() {
        transitionTo(SessionState.ASSIST_REQUESTED, TransitionReason.ASSIST_REQUESTED, Map.of());
        this.assistRequestedAt = lastActivityAt;
        this.assignedStaff = null;
    }

    /** 혼자 볼게요 선택. 직원 알림을 보내지 않는다. (기능명세서 3.1.1) */
    public void browseAlone() {
        transitionTo(SessionState.SELF_BROWSING, TransitionReason.SELF_BROWSE_SELECTED, Map.of());
    }

    /** 고객이 도움 요청을 철회. 직원 BE 로 취소를 통지해야 한다. */
    public void cancelAssist() {
        transitionTo(SessionState.MOOD_ACTIVE, TransitionReason.ASSIST_CANCELLED, Map.of());
        this.assistRequestedAt = null;
    }

    /**
     * 직원이 응대를 마친다. <b>세션은 끝나지 않는다.</b>
     * <p>
     * 고객은 아직 거울 앞에 있을 수 있고 연출도 그대로 유지된다. 응대만 끝나므로
     * 담당 직원이 해제되고 상태는 연출로 돌아가며, 직원 대기 목록에서는 빠진다.
     */
    public void finishAssist() {
        Map<String, String> metadata = assignedStaff == null
                ? Map.of() : Map.of("staffId", assignedStaff.staffId());
        transitionTo(SessionState.MOOD_ACTIVE, TransitionReason.ASSIST_FINISHED, metadata);
        this.assignedStaff = null;
        this.assistRequestedAt = null;
    }

    /**
     * 정상 종료. <b>고객이 직접 마쳤을 때만 호출한다.</b>
     * <p>
     * 타임아웃({@link #timeout()})과 반드시 구분해야 한다. 핵심 지표가 완주 세션 수라
     * 둘을 같은 상태로 뭉치면 "경험을 마친 고객"과 "그냥 떠난 고객"이 섞인다.
     * <p>
     * 직원은 이 메서드를 부르지 않는다. 직원의 종료는 {@link #finishAssist()} 다.
     */
    public void complete() {
        transitionTo(SessionState.ENDED, TransitionReason.COMPLETED, Map.of());
        this.endReason = EndReason.COMPLETED;
    }

    // ------------------------------------------------------- 직원 BE 콜백 수신

    /**
     * 직원이 응대를 시작한다. 음악 볼륨 하향은 이 전이 이후 연출 계층이 처리한다.
     * <p>
     * 같은 직원의 재요청은 버튼 중복 클릭이나 재시도이므로 조용히 성공시킨다(멱등).
     * <p>
     * <b>중복 응대 방지(기능명세서 2.1.1)는 직원 화면 담당자의 몫이다.</b> 이미 다른
     * 직원이 점유 중인지 판정해 409 로 막는 로직을 직원 서비스에 두면 되고,
     * 현재 점유자는 {@link #assignedStaff()} 로 확인할 수 있다.
     */
    public void acceptAssist(StaffAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        if (assignedStaff != null && assignedStaff.staffId().equals(assignment.staffId())) {
            return; // 버튼 중복 클릭·재시도. 이벤트를 늘리지 않는다
        }
        transitionTo(SessionState.ASSIST_ACCEPTED, TransitionReason.ASSIST_ACCEPTED,
                Map.of("staffId", assignment.staffId()));
        this.assignedStaff = assignment;
    }

    /**
     * 직원이 응대를 놓는다. 다시 요청 대기 상태로 돌아가 다른 직원이 받을 수 있다.
     * 점유자 본인만 해제할 수 있는지는 직원 화면 쪽에서 검사한다.
     */
    public void releaseAssist() {
        Map<String, String> metadata = assignedStaff == null
                ? Map.of() : Map.of("staffId", assignedStaff.staffId());
        transitionTo(SessionState.ASSIST_REQUESTED, TransitionReason.ASSIST_RELEASED, metadata);
        this.assignedStaff = null;
    }

    // -------------------------------------------------------------- 종료 처리

    /** 미러 화면에서 명시적 리셋. 미응대 알림은 서비스 계층이 취소한다. */
    public void reset() {
        expire(TransitionReason.RESET, EndReason.RESET);
    }

    /** 무입력 시간 초과. 프론트의 useIdleTimeout 과 같은 임계값을 쓴다. */
    public void timeout() {
        expire(TransitionReason.TIMEOUT, EndReason.TIMEOUT);
    }

    private void expire(TransitionReason reason, EndReason endReason) {
        transitionTo(SessionState.EXPIRED, reason, Map.of());
        this.endReason = endReason;
    }

    /** SessionStore 의 evictExpired 가 만료 대상을 고를 때 사용. */
    public boolean isIdleFor(Duration threshold) {
        return !state.isTerminal()
                && Duration.between(lastActivityAt, clock.instant()).compareTo(threshold) >= 0;
    }

    // ---------------------------------------------------------------- 전이 코어

    /**
     * 모든 전이가 지나는 단일 경로. 화이트리스트 검증과 이벤트 적재를 함께 수행한다.
     * 테스트에서 임의 전이를 시도하기 위해 package-private 으로 열어 둔다.
     */
    void transitionTo(SessionState target, TransitionReason reason, Map<String, String> metadata) {
        if (!state.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(state, target);
        }
        SessionState previous = state;
        Instant now = clock.instant();
        state = target;
        lastActivityAt = now;
        events.add(new SessionEvent(id, mirrorId, previous, target, reason, now, metadata));
    }

    // ------------------------------------------------------------------- 조회

    public String id() {
        return id;
    }

    public String mirrorId() {
        return mirrorId;
    }

    public String storeId() {
        return storeId;
    }

    public String mirrorLabel() {
        return mirrorLabel;
    }

    public SessionState state() {
        return state;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant lastActivityAt() {
        return lastActivityAt;
    }

    public Optional<MoodAnalysis> analysis() {
        return Optional.ofNullable(analysis);
    }

    public Optional<MusicTrack> track() {
        return Optional.ofNullable(track);
    }

    /** 프로토타입 SessionStore.Entry 의 analysisFallback 과 같은 의미. */
    public boolean isAnalysisFallback() {
        return analysisFallback;
    }

    public Optional<StaffAssignment> assignedStaff() {
        return Optional.ofNullable(assignedStaff);
    }

    public Optional<Instant> assistRequestedAt() {
        return Optional.ofNullable(assistRequestedAt);
    }

    public Optional<EndReason> endReason() {
        return Optional.ofNullable(endReason);
    }

    /** 이번 처리 주기에 쌓인 전이 이벤트. */
    public List<SessionEvent> events() {
        return Collections.unmodifiableList(events);
    }

    /** 이벤트를 넘겨주고 버퍼를 비운다. 저장소에 적재한 뒤 호출한다. */
    public List<SessionEvent> drainEvents() {
        List<SessionEvent> drained = List.copyOf(events);
        events.clear();
        return drained;
    }

    /** 응답에 실을 경과 시간. 단말 시계를 쓰지 않는다. */
    public long elapsedSeconds() {
        return Duration.between(startedAt, clock.instant()).toSeconds();
    }

    @Override
    public String toString() {
        return "Session{id=%s, mirrorId=%s, state=%s}".formatted(id, mirrorId, state);
    }
}
