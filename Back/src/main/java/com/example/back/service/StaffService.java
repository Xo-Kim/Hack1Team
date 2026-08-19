package com.example.back.service;

import com.example.back.domain.Session;
import com.example.back.domain.SessionState;
import com.example.back.domain.StaffAssignment;
import com.example.back.dto.ApiPayloads.RecommendResponse;
import com.example.back.dto.MoodAnalysis;
import com.example.back.dto.Outfit;
import com.example.back.dto.StaffPayloads.AcceptAssistRequest;
import com.example.back.dto.StaffPayloads.StaffCard;
import com.example.back.dto.StaffPayloads.StaffSessionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 직원 응대 화면의 조회·점유를 담당한다. {@code StaffController} 전용이다.
 * <p>
 * 제품 추천이 시스템 밖으로 나가는 유일한 통로이며, 그래서 중복 응대 판정도 여기 있다.
 * 도메인({@code Session})은 "누가 점유 중인가"만 들고 있고, 그것을 거부 사유로 삼을지는
 * 직원 화면의 정책이라 서비스 계층이 정한다.
 */
@Service
public class StaffService {

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);

    private final SessionStore sessions;
    private final MirrorService mirror;
    private final Clock clock;

    /**
     * 세션별 추천 캐시.
     * <p>
     * {@code MirrorService.recommend()} 는 호출마다 LLM 랭킹을 새로 돌린다. 직원이 카드를
     * 열 때마다, 혹은 화면이 폴링할 때마다 그게 돌면 무료 등급 분당 3회 한도를 즉시 넘기고,
     * 429 는 폴백이 조용히 받아버려 화면상으로는 정상처럼 보인다. 같은 착장에 대한 추천은
     * 어차피 바뀔 이유가 없으므로 세션당 한 번만 계산한다.
     */
    private final Map<String, RecommendResponse> recommendationCache = new ConcurrentHashMap<>();

    /**
     * 계산이 진행 중인 세션. 중복 실행을 막는다.
     * <p>
     * 직원 화면이 추천을 기다리며 폴링하고 직원이 여럿 붙을 수도 있어서, 막지 않으면
     * 같은 세션에 대해 랭킹이 여러 번 돈다. 분당 3회 한도를 혼자 다 쓰게 된다.
     */
    private final Set<String> computing = ConcurrentHashMap.newKeySet();

    public StaffService(SessionStore sessions, MirrorService mirror, Clock clock) {
        this.sessions = sessions;
        this.mirror = mirror;
        this.clock = clock;
    }

    /**
     * 직원 대기 화면의 세션 목록.
     * <p>
     * 도움 요청 중인 세션이 위로, 그중에서도 오래 기다린 순으로 온다. 응대 중이거나
     * '혼자 볼게요'를 고른 세션도 함께 내려가지만 {@code needsAssist=false} 다.
     * 응대 불필요 상태를 목록에서 아예 빼면 직원이 그 미러를 비어 있는 것으로 오해한다.
     */
    public List<StaffSessionSummary> list() {
        List<Session> visible = sessions.findStaffVisible();

        // 사라진 세션의 추천이 캐시에 남지 않도록 같이 청소한다. 폴링 경로라 자주 돈다.
        recommendationCache.keySet().removeIf(id -> sessions.get(id).isEmpty());

        return visible.stream()
                .map(this::toSummary)
                .sorted(Comparator
                        .comparing(StaffSessionSummary::needsAssist).reversed()
                        .thenComparing(s -> s.waitingSeconds() == null ? 0L : -s.waitingSeconds()))
                .toList();
    }

    /** 응대 카드. 무드·팔레트·컨셉명과 카테고리별 추천을 한 번에 돌려준다. */
    public StaffCard card(String sessionId) {
        return toCard(require(sessionId));
    }

    /**
     * 응대 시작. 다른 직원이 이미 점유 중이면 409 로 막는다 — 중복 응대 방지.
     * <p>
     * 같은 직원의 재요청은 버튼 중복 클릭이나 재시도이므로 조용히 성공한다.
     * <p>
     * '혼자 볼게요'를 선택한 세션은 상태 전이 자체가 막혀 있어 여기서 별도로 검사하지
     * 않는다. 도메인이 {@code SELF_BROWSING → ASSIST_ACCEPTED} 를 허용하지 않으므로
     * 409(illegal_state)가 난다. 고객이 거절한 응대를 직원이 밀어붙일 수 없다.
     */
    public StaffCard accept(String sessionId, AcceptAssistRequest request) {
        requireText(request == null ? null : request.staffId(), "staffId");

        Session session = require(sessionId);
        checkOwner(session, request.staffId());

        session.acceptAssist(new StaffAssignment(request.staffId(), clock.instant()));
        sessions.save(session);

        log.info("assist accepted sessionId={} staffId={} mirrorId={}",
                session.id(), request.staffId(), session.mirrorId());
        return toCard(session);
    }

    /**
     * 응대 해제. 세션은 다시 대기 목록으로 돌아가 다른 직원이 받을 수 있다.
     * 점유자 본인만 놓을 수 있다.
     */
    public StaffCard release(String sessionId, String staffId) {
        requireText(staffId, "staffId");

        Session session = require(sessionId);
        checkOwner(session, staffId);

        session.releaseAssist();
        sessions.save(session);

        log.info("assist released sessionId={} staffId={}", session.id(), staffId);
        return toCard(session);
    }

    /**
     * 응대 완료. <b>세션을 끝내지 않는다.</b>
     * <p>
     * 직원의 일이 끝난 것이지 고객의 경험이 끝난 것이 아니다. 고객은 아직 거울 앞에
     * 서 있을 수 있고 연출도 그대로 유지된다. 여기서 세션을 닫으면 고객 화면이 갑자기
     * 대기 화면으로 돌아가 <b>쫓아내는 신호</b>가 된다.
     * <p>
     * 세션을 끝내는 것은 고객의 '마치기'({@code POST /api/mirror/sessions/{id}/end})와
     * 무입력 타임아웃뿐이다. 다른 직원이 점유 중이면 막는다.
     * <p>
     * 추천 캐시는 지우지 않는다. 고객이 다시 도움을 부르면 같은 착장에 대한 추천이
     * 필요한데, 지우면 LLM 랭킹이 한 번 더 돌아 분당 한도를 쓴다.
     */
    public StaffCard complete(String sessionId, String staffId) {
        Session session = require(sessionId);
        if (staffId != null && !staffId.isBlank()) {
            checkOwner(session, staffId);
        }

        session.finishAssist();
        sessions.save(session);

        log.info("assist finished sessionId={} staffId={} 경과 {}초",
                session.id(), staffId, session.elapsedSeconds());
        return toCard(session);
    }

    // ------------------------------------------------------------------ 내부

    /** 점유자가 있고 그게 요청자가 아니면 거부한다. 점유자가 없으면 통과. */
    private void checkOwner(Session session, String staffId) {
        session.assignedStaff()
                .filter(current -> !current.staffId().equals(staffId))
                .ifPresent(current -> {
                    log.warn("중복 응대 차단 sessionId={} 점유={} 시도={}",
                            session.id(), current.staffId(), staffId);
                    throw new AssistConflictException();
                });
    }

    private StaffSessionSummary toSummary(Session session) {
        return new StaffSessionSummary(
                session.id(),
                session.mirrorId(),
                session.mirrorLabel(),
                session.state().name(),
                session.elapsedSeconds(),
                waitingSeconds(session),
                session.analysis().map(MoodAnalysis::conceptName).orElse(null),
                session.state() == SessionState.ASSIST_REQUESTED,
                session.assignedStaff().map(StaffAssignment::staffId).orElse(null));
    }

    private StaffCard toCard(Session session) {
        MoodAnalysis analysis = session.analysis().orElse(null);
        Outfit outfit = analysis == null ? null : analysis.outfit();
        RecommendResponse rec = cachedRecommendations(session);

        // 분석 전이면 기다릴 것이 없다. 이때 false 로 내리면 화면이 영원히 폴링한다.
        boolean ready = rec != null || analysis == null;

        return new StaffCard(
                session.id(),
                session.mirrorId(),
                session.mirrorLabel(),
                session.state().name(),
                session.elapsedSeconds(),
                waitingSeconds(session),
                analysis == null ? null : analysis.conceptName(),
                analysis == null ? null : analysis.mood(),
                outfit == null ? null : outfit.palette(),
                outfit == null ? null : outfit.style(),
                outfit == null ? null : outfit.formality(),
                analysis == null ? null : analysis.styleComment(),
                rec == null ? List.of() : rec.recommendations(),
                rec == null ? null : rec.stylingNote(),
                session.isAnalysisFallback(),
                rec != null && rec.fallback(),
                ready,
                rec != null ? rec.note()
                        : analysis == null ? "분석이 아직 끝나지 않아 추천을 만들지 못했습니다." : null,
                session.assignedStaff().map(StaffAssignment::staffId).orElse(null));
    }

    /**
     * 고객이 도움을 요청하는 순간 추천 계산을 미리 시작한다.
     * <p>
     * <b>이 시점이 예열의 적기다.</b> 직원이 카드를 열 것이 확정된 순간이면서, 알림을
     * 보고 손을 뻗기까지 몇 초의 여유가 실제로 존재한다. 그 여유에 5초짜리 랭킹을
     * 밀어 넣으면 직원이 체감하는 대기는 사라진다.
     * <p>
     * 분석 직후가 아니라 여기인 이유: '혼자 볼게요'를 고른 고객의 추천은 아무도 보지
     * 않는데, 무료 등급은 분당 3회라 그 낭비가 곧 429 가 되어 돌아온다. 429 는 폴백이
     * 조용히 받아버려 화면상으로는 정상처럼 보이므로 더 나쁘다.
     */
    @Async
    @EventListener
    public void warmOnAssistRequest(SessionTransitionEvent transition) {
        if (transition.event().to() != SessionState.ASSIST_REQUESTED) {
            return;
        }
        sessions.get(transition.event().sessionId()).ifPresent(this::computeIfNeeded);
    }

    /**
     * 추천을 캐시에서 꺼낸다. 없으면 계산을 <b>시작만</b> 하고 비어 있는 채로 돌아간다.
     * <p>
     * 예전에는 여기서 계산이 끝날 때까지 기다렸고, 그래서 카드를 누르고 5.2초 동안
     * 화면에 아무것도 뜨지 않았다(실측: 5.2초 중 5.0초가 랭킹). 직원이 고객에게
     * 걸어가면서 당장 필요한 것은 무드·팔레트·컨셉명이지 추천 목록이 아니다.
     * 먼저 보낼 수 있는 것을 먼저 보내고, 추천은 준비되는 대로 따라붙는다.
     */
    private RecommendResponse cachedRecommendations(Session session) {
        RecommendResponse cached = recommendationCache.get(session.id());
        if (cached == null) {
            // 폴링이 곧 재시도가 된다 — 예열이 실패했더라도 여기서 다시 걸린다.
            warm(session);
        }
        return cached;
    }

    /** 자기 호출이라 프록시를 타지 않는다. 비동기 실행은 이 메서드에 붙은 @Async 가 맡는다. */
    @Async
    void warm(Session session) {
        computeIfNeeded(session);
    }

    /**
     * 실제 계산. 같은 세션에 대해 한 번만 돈다.
     * <p>
     * {@code computeIfAbsent} 를 쓰지 않는 이유: 랭킹 호출이 실측 3~7초인데 그동안
     * ConcurrentHashMap 의 해당 버킷이 잠긴다. 대신 진행 중 표시로 중복을 막는다.
     */
    private void computeIfNeeded(Session session) {
        String id = session.id();
        if (recommendationCache.containsKey(id) || !computing.add(id)) {
            return;
        }
        try {
            long started = System.currentTimeMillis();
            // 분석 전이면 비어 있다. 이때는 캐시에 넣지 않아야 분석 후 다시 계산된다.
            mirror.recommend(session).ifPresent(computed -> {
                recommendationCache.putIfAbsent(id, computed);
                log.info("추천 예열 완료 sessionId={} {}ms", id, System.currentTimeMillis() - started);
            });
        } catch (Exception e) {
            // 예열 실패가 응대를 막아서는 안 된다. 다음 카드 조회가 다시 시도한다.
            log.warn("추천 예열 실패 sessionId={} — 다음 조회에서 재시도합니다: {}", id, e.toString());
        } finally {
            computing.remove(id);
        }
    }

    private Long waitingSeconds(Session session) {
        return session.assistRequestedAt()
                .map(at -> Duration.between(at, clock.instant()).toSeconds())
                .orElse(null);
    }

    private Session require(String sessionId) {
        return sessions.get(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 가 비어 있습니다");
        }
    }
}
