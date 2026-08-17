package com.example.back.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 직원 응대 API 계약 검증. 키 없이 폴백 경로로만 돈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mood-mirror.llm.api-key=",
        "mood-mirror.music.jamendo.client-id="
})
class StaffControllerTest {

    private static final String IMAGE = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(
            new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});

    private static final String STAFF_1 = "{\"staffId\":\"staff-01\",\"staffName\":\"김직원\"}";
    private static final String STAFF_2 = "{\"staffId\":\"staff-02\",\"staffName\":\"이직원\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    // ------------------------------------------------------------- 대기 목록

    @Test
    @DisplayName("연출 중인 세션은 대기 목록에 오르지 않는다 — 직원이 갈 이유가 없다")
    void moodActiveSessionIsNotListed() throws Exception {
        String id = analyzedSession();

        mvc.perform(get("/api/staff/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sessionId=='" + id + "')]").isEmpty());
    }

    @Test
    @DisplayName("도움을 요청하면 목록에 오르고 needsAssist 가 켜진다")
    void assistRequestAppearsInList() throws Exception {
        String id = analyzedSession();
        mvc.perform(post("/api/mirror/sessions/{id}/assist-request", id));

        mvc.perform(get("/api/staff/sessions"))
                .andExpect(jsonPath("$[?(@.sessionId=='" + id + "')].needsAssist").value(true))
                .andExpect(jsonPath("$[?(@.sessionId=='" + id + "')].mirrorLabel").value("테스트 미러"));
    }

    @Test
    @DisplayName("혼자 보기 세션은 목록에 남되 응대 대상은 아니다")
    void selfBrowsingStaysVisibleButNotActionable() throws Exception {
        String id = analyzedSession();
        mvc.perform(post("/api/mirror/sessions/{id}/self-browse", id));

        // 목록에서 아예 빼면 직원이 그 미러를 비어 있는 것으로 오해한다.
        mvc.perform(get("/api/staff/sessions"))
                .andExpect(jsonPath("$[?(@.sessionId=='" + id + "')].state").value("SELF_BROWSING"))
                .andExpect(jsonPath("$[?(@.sessionId=='" + id + "')].needsAssist").value(false));
    }

    @Test
    @DisplayName("대기 목록에는 추천이 실리지 않는다 — 폴링마다 LLM 을 돌릴 수 없다")
    void listCarriesNoRecommendations() throws Exception {
        String id = analyzedSession();
        mvc.perform(post("/api/mirror/sessions/{id}/assist-request", id));

        mvc.perform(get("/api/staff/sessions"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("recommendations"))));
    }

    // ------------------------------------------------------------- 응대 카드

    @Test
    @DisplayName("응대 카드에 무드·팔레트·컨셉명과 카테고리별 추천이 함께 온다")
    void cardCarriesMoodAndRecommendations() throws Exception {
        String id = analyzedSession();

        mvc.perform(get("/api/staff/sessions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conceptName").isNotEmpty())
                .andExpect(jsonPath("$.mood").isNotEmpty())
                .andExpect(jsonPath("$.palette").isArray())
                .andExpect(jsonPath("$.recommendations.length()").value(3))
                .andExpect(jsonPath("$.recommendations[0].items.length()").value(3))
                .andExpect(jsonPath("$.recommendations[0].items[0].product.name").isNotEmpty());
    }

    @Test
    @DisplayName("추천 제품은 모두 카탈로그 실존 항목이다 — 할루시네이션 차단")
    void recommendationsAreRealProducts() throws Exception {
        String id = analyzedSession();

        mvc.perform(get("/api/staff/sessions/{id}", id))
                .andExpect(jsonPath("$.recommendations[*].items[*].productId").isNotEmpty())
                .andExpect(jsonPath("$.recommendations[*].items[*].product.priceKrw").isNotEmpty());
    }

    @Test
    @DisplayName("없는 세션의 카드는 404")
    void unknownSessionCardIs404() throws Exception {
        mvc.perform(get("/api/staff/sessions/{id}", "no-such-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("session_not_found"));
    }

    // --------------------------------------------------- 중복 응대 방지 (핵심)

    @Test
    @DisplayName("다른 직원이 점유 중이면 409 assist_conflict")
    void secondStaffIsBlocked() throws Exception {
        String id = requestedSession();

        mvc.perform(post("/api/staff/sessions/{id}/accept", id)
                        .contentType(MediaType.APPLICATION_JSON).content(STAFF_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ASSIST_ACCEPTED"))
                .andExpect(jsonPath("$.assignedStaffName").value("김직원"));

        mvc.perform(post("/api/staff/sessions/{id}/accept", id)
                        .contentType(MediaType.APPLICATION_JSON).content(STAFF_2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("assist_conflict"));
    }

    @Test
    @DisplayName("같은 직원의 재클릭은 멱등하게 성공한다")
    void sameStaffAcceptIsIdempotent() throws Exception {
        String id = requestedSession();

        mvc.perform(post("/api/staff/sessions/{id}/accept", id)
                .contentType(MediaType.APPLICATION_JSON).content(STAFF_1));
        mvc.perform(post("/api/staff/sessions/{id}/accept", id)
                        .contentType(MediaType.APPLICATION_JSON).content(STAFF_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ASSIST_ACCEPTED"));
    }

    @Test
    @DisplayName("혼자 보기를 고른 고객에게는 직원이 응대를 밀어붙일 수 없다")
    void cannotForceAssistOnSelfBrowsing() throws Exception {
        String id = analyzedSession();
        mvc.perform(post("/api/mirror/sessions/{id}/self-browse", id));

        mvc.perform(post("/api/staff/sessions/{id}/accept", id)
                        .contentType(MediaType.APPLICATION_JSON).content(STAFF_1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("illegal_state"));
    }

    @Test
    @DisplayName("staffId 없이 점유하려 하면 400")
    void acceptWithoutStaffIdIs400() throws Exception {
        String id = requestedSession();

        mvc.perform(post("/api/staff/sessions/{id}/accept", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffName\":\"이름만\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    // --------------------------------------------------------------- 해제·종료

    @Test
    @DisplayName("점유자가 아니면 해제할 수 없다")
    void onlyOwnerCanRelease() throws Exception {
        String id = acceptedSession();

        mvc.perform(post("/api/staff/sessions/{id}/release", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":\"staff-02\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/staff/sessions/{id}/release", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":\"staff-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ASSIST_REQUESTED"));
    }

    @Test
    @DisplayName("응대 완료는 ENDED — 타임아웃(EXPIRED)과 구분되어야 완주 세션을 셀 수 있다")
    void completeEndsSessionAsCompleted() throws Exception {
        String id = acceptedSession();

        mvc.perform(post("/api/staff/sessions/{id}/complete", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":\"staff-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ENDED"));

        mvc.perform(get("/api/staff/sessions/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("고객이 리셋한 세션은 직원 목록에서 사라진다")
    void resetRemovesFromStaffList() throws Exception {
        String id = requestedSession();
        mvc.perform(post("/api/mirror/sessions/{id}/reset", id));

        mvc.perform(get("/api/staff/sessions"))
                .andExpect(jsonPath("$[?(@.sessionId=='" + id + "')]").isEmpty());
    }

    // ------------------------------------------------------------------ 헬퍼

    private String analyzedSession() throws Exception {
        String body = mvc.perform(post("/api/mirror/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mirrorId":"mirror-test","storeId":"mcm-test","mirrorLabel":"테스트 미러"}"""))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("sessionId").asString();

        mvc.perform(post("/api/mirror/sessions/{id}/consent", id));
        mvc.perform(post("/api/mirror/sessions/{id}/analyze", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"" + IMAGE + "\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private String requestedSession() throws Exception {
        String id = analyzedSession();
        mvc.perform(post("/api/mirror/sessions/{id}/assist-request", id));
        return id;
    }

    private String acceptedSession() throws Exception {
        String id = requestedSession();
        mvc.perform(post("/api/staff/sessions/{id}/accept", id)
                .contentType(MediaType.APPLICATION_JSON).content(STAFF_1));
        return id;
    }
}
