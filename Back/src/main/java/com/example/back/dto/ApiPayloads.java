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
    public record AnalyzeRequest(String image) {
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
