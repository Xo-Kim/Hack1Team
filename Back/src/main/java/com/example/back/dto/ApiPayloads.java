package com.example.back.dto;

import java.util.List;

/** 프론트와 주고받는 요청/응답 페이로드 묶음. */
public final class ApiPayloads {

    private ApiPayloads() {
    }

    /**
     * 분석 요청. {@code image} 는 {@code data:image/jpeg;base64,...} 형태의 data URL.
     * <p>
     * multipart 대신 JSON 을 쓰는 이유: multipart 는 임계값을 넘으면 서블릿 컨테이너가
     * 디스크에 임시 파일을 만든다. "이미지를 저장하지 않는다"(PRD §16.3)를 지키려면
     * 디스크 경로 자체를 만들지 않는 편이 확실하다.
     */
    public record AnalyzeRequest(String sessionId, String image) {
    }

    /**
     * 세션 시작 요청. 분석보다 먼저 세션을 여는 이유는 직원 알림에 어느 미러인지가
     * 필요하고, 고객이 동의를 거부하거나 중간에 이탈한 경우도 지표에 남겨야 하기 때문이다.
     */
    public record StartSessionRequest(String mirrorId, String storeId, String mirrorLabel) {
    }

    public record StartSessionResponse(String sessionId, String state) {
    }

    /**
     * 고객 미러 화면용 세션 상태. 폴링과 상태 변화 확인에 쓴다.
     * <p>
     * <b>이 레코드에 제품 추천 필드를 추가하지 말 것.</b> 고객 화면에 AI 추천을
     * 노출하지 않는 것이 이 서비스의 설계 원칙이며, 같은 서버를 쓰는 이상
     * 응답 DTO 분리가 그 원칙을 지키는 장치다.
     *
     * @param musicDucked  직원 응대가 시작돼 음악 볼륨을 낮춰야 하는지.
     *                     클라이언트가 상태로 추론하지 않도록 서버가 직접 알려준다
     * @param assistStaffName 응대 중인 직원 이름. 고객 화면의 안내 문구에 쓴다
     */
    public record SessionStateResponse(
            String sessionId,
            String state,
            long elapsedSeconds,
            MoodAnalysis analysis,
            MusicTrack track,
            boolean fallback,
            boolean musicDucked,
            String assistStaffName
    ) {
    }

    /**
     * 분석 응답. 이미지는 응답에도 세션에도 남지 않는다.
     * <p>
     * {@code track} 이 null 이면 프론트가 Web Audio 절차적 앰비언스로 폴백한다.
     */
    public record AnalyzeResponse(
            String sessionId,
            MoodAnalysis analysis,
            MusicTrack track,
            boolean fallback,
            String note
    ) {
    }

    /** 추천 응답. 실제 서비스에서는 직원 단말로만 전달된다. (PRD §1.1) */
    public record RecommendResponse(
            String sessionId,
            List<CategoryRecommendation> recommendations,
            String stylingNote,
            boolean fallback,
            String note
    ) {
    }

    public record ErrorResponse(String error, String message) {
    }
}
