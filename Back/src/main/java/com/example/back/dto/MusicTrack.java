package com.example.back.dto;

/**
 * 실제로 재생할 음원 1곡.
 * <p>
 * {@code null} 이면 프론트가 Web Audio 절차적 앰비언스로 폴백한다 — 어떤 경우에도
 * 소리가 안 나는 상태는 만들지 않는다. (PRD §18)
 * <p>
 * {@code artist} 와 {@code shareUrl} 은 장식이 아니다. Jamendo 의 CC 라이선스는
 * 대부분 저작자 표시(BY)를 요구하므로 화면에 반드시 노출해야 한다.
 */
public record MusicTrack(
        String id,
        String title,
        String artist,
        String audioUrl,
        int durationSec,
        String shareUrl,
        String license,
        String source,
        /** GPT 가 후보 중 이 곡을 고른 이유. 폴백(첫 후보 사용) 시 null. */
        String reason,
        /**
         * 상업적 이용 가능 여부 (Jamendo 응답의 licenses.ccnc 가 false 인가).
         * 해커톤 시연은 무관하지만 실제 매장 영업은 상업적 이용이다. (PRD §19)
         */
        boolean commercialOk
) {
}
