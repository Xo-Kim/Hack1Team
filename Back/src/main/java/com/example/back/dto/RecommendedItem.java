package com.example.back.dto;

/**
 * 추천 1건.
 * <p>
 * LLM 은 {@code productId} 와 {@code reason} 만 생성한다.
 * {@code product} 는 서버가 카탈로그에서 조회해 채운다 — 제품명·가격·위치를
 * LLM 이 생성하게 두면 할루시네이션이 그대로 직원에게 전달된다. (PRD §12.2)
 */
public record RecommendedItem(
        int rank,
        String productId,
        String reason,
        Product product
) {
}
