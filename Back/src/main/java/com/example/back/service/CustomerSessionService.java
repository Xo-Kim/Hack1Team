package com.example.back.service;

import com.example.back.domain.Session;
import com.example.back.domain.SessionState;
import com.example.back.dto.ApiPayloads.AnalyzeResponse;
import com.example.back.dto.ApiPayloads.SessionStateResponse;
import com.example.back.dto.ApiPayloads.StartSessionRequest;
import com.example.back.dto.ApiPayloads.StartSessionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 고객 미러 화면의 세션 흐름을 담당한다. {@code CustomerMirrorController} 전용이다.
 * <p>
 * <b>이 클래스는 {@code MirrorService.recommend()} 를 호출하지 않는다.</b>
 * 고객 경로에서 추천에 닿을 수 있는 코드가 하나도 없어야, 추천 비노출 원칙이
 * "프론트가 안 부르기로 한 약속"이 아니라 구조적 사실이 된다.
 * 추천은 {@link StaffService} 만 만진다.
 */
@Service
public class CustomerSessionService {

    private static final Logger log = LoggerFactory.getLogger(CustomerSessionService.class);

    private final SessionStore sessions;
    private final MirrorService mirror;

    public CustomerSessionService(SessionStore sessions, MirrorService mirror) {
        this.sessions = sessions;
        this.mirror = mirror;
    }

    /** 미러 대기 화면에서 고객이 반응하면 세션을 연다. */
    public StartSessionResponse start(StartSessionRequest request) {
        // Session 생성자가 null 을 거부하므로 여기서 안 막으면 NPE 가 500 으로 나간다.
        // 어느 미러인지 모르는 세션은 직원 알림에 띄울 수 없으니 애초에 만들지 않는다.
        requireText(request == null ? null : request.mirrorId(), "mirrorId");
        requireText(request.storeId(), "storeId");

        Session session = sessions.start(
                request.mirrorId(), request.storeId(), request.mirrorLabel());
        sessions.save(session);
        log.info("session started sessionId={} mirrorId={}", session.id(), session.mirrorId());
        return new StartSessionResponse(session.id(), session.state().name());
    }

    public SessionStateResponse state(String sessionId) {
        return toResponse(require(sessionId));
    }

    /** 촬영·분석 동의. 이후 3·2·1 카운트다운은 클라이언트에서만 진행된다. */
    public SessionStateResponse consent(String sessionId) {
        return apply(sessionId, Session::consent);
    }

    /**
     * 분석용 스틸컷 처리. 상태는 {@code ANALYZING} → {@code MOOD_ACTIVE} 로 두 번 움직인다.
     * <p>
     * LLM 이 실패해도 예외를 내지 않는다. 폴백 프리셋으로 같은 상태에 도달하며,
     * 구분은 응답의 {@code fallback} 플래그로만 남는다.
     */
    public AnalyzeResponse analyze(String sessionId, String image) {
        Session session = require(sessionId);
        AnalyzeResponse response = mirror.analyze(session, image);
        sessions.save(session);
        return response;
    }

    /** 직원 도움 받기. 이 세션이 직원 화면 대기 목록에 올라간다. */
    public SessionStateResponse requestAssist(String sessionId) {
        return apply(sessionId, Session::requestAssist);
    }

    /** 혼자 볼게요. 직원 알림을 보내지 않는다. */
    public SessionStateResponse browseAlone(String sessionId) {
        return apply(sessionId, Session::browseAlone);
    }

    /** 도움 요청 철회. 대기 목록에서 내려간다. */
    public SessionStateResponse cancelAssist(String sessionId) {
        return apply(sessionId, Session::cancelAssist);
    }

    /**
     * 고객이 경험을 마친다. 완주로 기록된다.
     * <p>
     * <b>세션을 끝낼 수 있는 것은 고객뿐이다.</b> 직원의 '응대 완료'는 응대만 닫고
     * 세션은 살려 둔다 — 고객이 아직 거울 앞에 있는데 화면이 꺼지면 그 자체로
     * 쫓아내는 신호가 되기 때문이다.
     * <p>
     * 타임아웃·중단({@link #reset})과 갈라 두는 이유는 지표다. 핵심 지표가 완주
     * 세션 수인데 종료 경로가 하나면 "마친 고객"과 "떠난 고객"이 섞인다.
     */
    public SessionStateResponse end(String sessionId) {
        return apply(sessionId, Session::complete);
    }

    /** 미러를 대기 화면으로 되돌린다. 미응대 알림도 함께 정리된다. */
    public SessionStateResponse reset(String sessionId) {
        return apply(sessionId, Session::reset);
    }

    // ------------------------------------------------------------------ 내부

    private SessionStateResponse apply(String sessionId, Consumer<Session> action) {
        Session session = require(sessionId);
        action.accept(session);
        sessions.save(session);
        return toResponse(session);
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

    /**
     * 종료된 세션에서는 연출 정보를 비운다. 만료 직후 도착한 폴링 응답이
     * 조명·음악을 되살리는 것을 막는다.
     */
    private static SessionStateResponse toResponse(Session session) {
        boolean terminal = session.state().isTerminal();
        return new SessionStateResponse(
                session.id(),
                session.state().name(),
                session.elapsedSeconds(),
                terminal ? null : session.analysis().orElse(null),
                terminal ? null : session.track().orElse(null),
                session.isAnalysisFallback(),
                session.state() == SessionState.ASSIST_ACCEPTED);
    }
}
