package com.example.back.dto;

/**
 * 하드웨어 중립 조명 스펙. (PRD §14.3)
 * <p>
 * 이 객체는 특정 출력 장치에 종속되지 않는다. 현재는 웹 렌더러(CSS/Canvas)가 소비하지만,
 * 동일한 스펙을 Philips Hue REST 어댑터나 DMX512 어댑터가 그대로 소비할 수 있어야 한다.
 * 따라서 여기에 CSS 전용 속성을 추가하지 말 것.
 */
public record Lighting(
        String sceneName,
        String primaryColor,
        String accentColor,
        int colorTemperatureK,
        double brightness,
        int transitionMs,
        String effect
) {
}
