package com.example.back.dto;

import java.util.List;

/** 카테고리(bag/wallet/belt) 단위 추천 묶음. 항상 3개를 채운다. */
public record CategoryRecommendation(
        String category,
        List<RecommendedItem> items
) {
}
