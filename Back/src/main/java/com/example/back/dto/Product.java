package com.example.back.dto;

import java.util.List;

/** 제품 카탈로그 항목. (PRD §11) */
public record Product(
        String id,
        String category,
        String name,
        String line,
        List<String> colors,
        String material,
        long priceKrw,
        List<String> styleTags,
        double formality,
        String size,
        String imageUrl,
        String productUrl,
        String storeLocation
) {
}
