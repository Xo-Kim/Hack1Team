package com.example.back.service;

import com.example.back.dto.Lighting;
import com.example.back.dto.MoodAnalysis;
import com.example.back.dto.MusicSpec;
import com.example.back.dto.Outfit;

import java.util.List;

/**
 * LLM 실패·타임아웃·mock 모드에서 쓰는 사전 정의 프리셋 5종. (PRD §18)
 * <p>
 * 이미지 바이트 해시로 프리셋을 고르므로 <b>같은 사진은 항상 같은 결과</b>가 된다.
 * 랜덤으로 하면 데모 중 같은 착장에서 결과가 튀어 신뢰를 잃는다.
 */
public final class FallbackPresets {

    private FallbackPresets() {
    }

    private static final List<MoodAnalysis> PRESETS = List.of(
            new MoodAnalysis(
                    new Outfit(List.of("black", "cognac"), "street-luxury", 0.45,
                            List.of("street", "monochrome", "night", "edgy")),
                    "confident-night",
                    "Munich Midnight",
                    new Lighting("Munich Midnight", "#1a1a2e", "#c8873c", 2700, 0.52, 2600, "breathe"),
                    new MusicSpec(List.of("dark r&b", "night drive"), 0.55, "hiphop-rnb", "D", "minor", 82),
                    "밤으로 기울어진 톤. 조명을 낮추고 따뜻한 포인트만 남겼습니다."
            ),
            new MoodAnalysis(
                    new Outfit(List.of("white", "sand"), "clean-minimal", 0.7,
                            List.of("minimal", "elegant", "day", "classic")),
                    "calm-daylight",
                    "Seoul Morning",
                    new Lighting("Seoul Morning", "#e8e2d8", "#c9b79a", 5200, 0.78, 2400, "static"),
                    new MusicSpec(List.of("ambient", "soft piano"), 0.3, "ambient", "F", "major", 68),
                    "밝고 정돈된 무드. 빛을 넓게 펴서 실루엣을 선명하게 두었습니다."
            ),
            new MoodAnalysis(
                    new Outfit(List.of("red", "black"), "bold-statement", 0.5,
                            List.of("bold", "statement", "night", "luxe")),
                    "electric-bold",
                    "Neon Ruby",
                    new Lighting("Neon Ruby", "#2b0a14", "#e0345a", 2200, 0.6, 2200, "sweep"),
                    new MusicSpec(List.of("synthwave", "electro"), 0.8, "electronic", "A", "phrygian", 104),
                    "강한 색이 중심에 있는 룩. 조명도 같은 온도로 맞췄습니다."
            ),
            new MoodAnalysis(
                    new Outfit(List.of("navy", "grey"), "quiet-formal", 0.8,
                            List.of("formal", "minimal", "classic", "monochrome")),
                    "composed-evening",
                    "Berlin Blue Hour",
                    new Lighting("Berlin Blue Hour", "#141c2e", "#6d8bb5", 4000, 0.5, 2800, "breathe"),
                    new MusicSpec(List.of("downtempo", "neo classical"), 0.35, "downtempo", "C", "dorian", 74),
                    "차분하게 정리된 실루엣. 푸른 기가 도는 빛이 어울립니다."
            ),
            new MoodAnalysis(
                    new Outfit(List.of("green", "cognac"), "retro-casual", 0.35,
                            List.of("retro", "casual", "day", "unisex")),
                    "warm-retro",
                    "Autumn Studio",
                    new Lighting("Autumn Studio", "#241d12", "#d9a441", 3000, 0.62, 2500, "breathe"),
                    new MusicSpec(List.of("soul", "vintage funk"), 0.5, "soul", "G", "minor", 88),
                    "따뜻한 계열이 겹친 룩. 빛도 같은 방향으로 얹었습니다."
            )
    );

    public static MoodAnalysis pick(byte[] imageBytes) {
        int idx = imageBytes == null || imageBytes.length == 0
                ? 0
                : Math.floorMod(stableHash(imageBytes), PRESETS.size());
        return PRESETS.get(idx);
    }

    public static MoodAnalysis first() {
        return PRESETS.get(0);
    }

    /**
     * 이미지 전체가 아니라 균등 샘플 64바이트만으로 해시한다.
     * 조명·노이즈 차이로 매 프레임 해시가 바뀌면 프리셋이 튀기 때문.
     */
    private static int stableHash(byte[] bytes) {
        int step = Math.max(1, bytes.length / 64);
        int h = 17;
        for (int i = 0; i < bytes.length; i += step) {
            h = h * 31 + (bytes[i] & 0xF0);
        }
        return h;
    }
}
