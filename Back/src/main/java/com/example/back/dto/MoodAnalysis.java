package com.example.back.dto;

/**
 * 1차 Vision 호출의 전체 결과. (PRD §13.1)
 * <p>
 * styleComment 는 고객 화면에 노출되는 유일한 LLM 생성 텍스트이므로
 * 제품명·카테고리를 절대 포함해서는 안 된다. (PRD §1.1 / §13.3)
 */
public record MoodAnalysis(
        Outfit outfit,
        String mood,
        String conceptName,
        Lighting lighting,
        MusicSpec music,
        String styleComment
) {
}
