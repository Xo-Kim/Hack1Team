package com.example.back.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 고객 미러 API 계약 검증.
 * <p>
 * <b>API 키를 빈 값으로 강제한다.</b> {@code application.yaml} 이
 * {@code application-local.yaml} 을 import 하므로, 개발자 로컬에 실제 키가 있으면
 * 테스트가 진짜 OpenAI 를 호출한다. 느리고, 돈이 들고, 분당 한도에 걸려 결과가
 * 들쭉날쭉해진다. 폴백 프리셋은 이미지 해시 기준이라 오히려 결정적이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mood-mirror.llm.api-key=",
        "mood-mirror.music.jamendo.client-id="
})
class CustomerMirrorControllerTest {

    /** 1x1 JPEG. 내용은 중요하지 않다 — 폴백 경로만 타면 된다. */
    private static final String IMAGE = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(
            new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    // ------------------------------------------------------------- 세션 시작

    @Test
    @DisplayName("mirrorId 없이 세션을 열면 400 — 어느 미러인지 모르는 세션은 직원 화면에 띄울 수 없다")
    void rejectsSessionWithoutMirrorId() throws Exception {
        mvc.perform(post("/api/mirror/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"mcm-seoul\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    @DisplayName("세션을 열면 IDLE 상태로 시작한다")
    void startsInIdle() throws Exception {
        mvc.perform(post("/api/mirror/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDLE"))
                .andExpect(jsonPath("$.sessionId").isNotEmpty());
    }

    // ------------------------------------------------------------- 분석 경로

    @Test
    @DisplayName("동의 → 분석까지 진행하면 MOOD_ACTIVE 에 도달한다")
    void reachesMoodActive() throws Exception {
        String id = openConsentedSession();

        mvc.perform(post("/api/mirror/sessions/{id}/analyze", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(imageBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.conceptName").isNotEmpty())
                // 키가 없으므로 반드시 폴백 프리셋이어야 한다
                .andExpect(jsonPath("$.fallback").value(true));

        mvc.perform(get("/api/mirror/sessions/{id}", id))
                .andExpect(jsonPath("$.state").value("MOOD_ACTIVE"));
    }

    @Test
    @DisplayName("동의 전에 분석하면 409 — 상태 머신이 순서를 강제한다")
    void rejectsAnalyzeBeforeConsent() throws Exception {
        String id = openSession();

        mvc.perform(post("/api/mirror/sessions/{id}/analyze", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(imageBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("illegal_state"));
    }

    @Test
    @DisplayName("깨진 이미지는 400이고, 세션은 CONSENTED 로 남아 재촬영할 수 있다")
    void brokenImageKeepsSessionRetryable() throws Exception {
        String id = openConsentedSession();

        mvc.perform(post("/api/mirror/sessions/{id}/analyze", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"not-base64!!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        // 검증이 상태 전이보다 먼저 일어나야 여기가 ANALYZING 이 아니다.
        // ANALYZING 에 갇히면 재촬영이 영원히 409 가 된다.
        mvc.perform(get("/api/mirror/sessions/{id}", id))
                .andExpect(jsonPath("$.state").value("CONSENTED"));

        mvc.perform(post("/api/mirror/sessions/{id}/analyze", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(imageBody()))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------- 추천 비노출 (핵심 원칙)

    @Test
    @DisplayName("고객 응답 어디에도 추천이 실리지 않는다")
    void neverExposesRecommendations() throws Exception {
        String id = analyzedSession();

        MvcResult result = mvc.perform(get("/api/mirror/sessions/{id}", id))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("recommendations")).isFalse();
        assertThat(body.has("stylingNote")).isFalse();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("productId");
    }

    @Test
    @DisplayName("고객 경로에는 추천 엔드포인트 자체가 없다")
    void noRecommendEndpointOnCustomerPath() throws Exception {
        String id = analyzedSession();

        mvc.perform(get("/api/mirror/sessions/{id}/recommend", id))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/recommend/{id}", id))
                .andExpect(status().isNotFound());
    }

    // --------------------------------------------------------- 고객의 선택

    @Test
    @DisplayName("도움 요청 후 철회하면 연출 상태로 돌아간다")
    void assistRequestThenCancel() throws Exception {
        String id = analyzedSession();

        mvc.perform(post("/api/mirror/sessions/{id}/assist-request", id))
                .andExpect(jsonPath("$.state").value("ASSIST_REQUESTED"));
        mvc.perform(post("/api/mirror/sessions/{id}/assist-cancel", id))
                .andExpect(jsonPath("$.state").value("MOOD_ACTIVE"));
    }

    @Test
    @DisplayName("혼자 보기를 골라도 마음이 바뀌면 직원을 부를 수 있다")
    void selfBrowseThenAssist() throws Exception {
        String id = analyzedSession();

        mvc.perform(post("/api/mirror/sessions/{id}/self-browse", id))
                .andExpect(jsonPath("$.state").value("SELF_BROWSING"));
        mvc.perform(post("/api/mirror/sessions/{id}/assist-request", id))
                .andExpect(jsonPath("$.state").value("ASSIST_REQUESTED"));
    }

    // ------------------------------------------------------------ 종료 처리

    @Test
    @DisplayName("리셋하면 EXPIRED 가 되고 이후 조회는 404")
    void resetExpiresSession() throws Exception {
        String id = analyzedSession();

        mvc.perform(post("/api/mirror/sessions/{id}/reset", id))
                .andExpect(jsonPath("$.state").value("EXPIRED"));

        mvc.perform(get("/api/mirror/sessions/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("session_not_found"));
    }

    @Test
    @DisplayName("없는 세션은 404, 없는 경로도 404 — 500 이 아니다")
    void unknownIdsAndPathsAre404() throws Exception {
        mvc.perform(get("/api/mirror/sessions/{id}", "no-such-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("session_not_found"));

        mvc.perform(get("/api/definitely-not-a-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    // ------------------------------------------------------------------ 헬퍼

    private String openSession() throws Exception {
        String body = mvc.perform(post("/api/mirror/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("sessionId").asString();
    }

    private String openConsentedSession() throws Exception {
        String id = openSession();
        mvc.perform(post("/api/mirror/sessions/{id}/consent", id)).andExpect(status().isOk());
        return id;
    }

    private String analyzedSession() throws Exception {
        String id = openConsentedSession();
        mvc.perform(post("/api/mirror/sessions/{id}/analyze", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(imageBody()))
                .andExpect(status().isOk());
        return id;
    }

    private String startBody() {
        return """
                {"mirrorId":"mirror-test","storeId":"mcm-test","mirrorLabel":"테스트 미러"}""";
    }

    private String imageBody() {
        return "{\"image\":\"" + IMAGE + "\"}";
    }
}
