package com.example.back.dto;

import java.util.List;

/** 1차 Vision 분석이 읽어낸 착장 정보. (PRD §13.1) */
public record Outfit(
        List<String> palette,
        String style,
        double formality,
        List<String> moodTags
) {
}
