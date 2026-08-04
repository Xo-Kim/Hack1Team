package com.example.back.service;

import com.example.back.dto.Outfit;
import com.example.back.dto.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 제품 카탈로그 보관 + 룰 기반 프리필터. (PRD §12.1)
 * <p>
 * 프리필터의 목적은 두 가지다.
 * 1. LLM 에 넘길 후보를 줄여 토큰과 지연을 아낀다.
 * 2. LLM 이 실패해도 이 점수만으로 추천을 만들 수 있게 한다 (폴백 경로).
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    public static final List<String> CATEGORIES = List.of("bag", "wallet", "belt");

    /** 후보군 크기. 카테고리당 이 개수만 LLM 에 넘긴다. */
    private static final int CANDIDATE_SIZE = 7;

    // 프리필터 가중치. 축적된 응대 데이터로 보정하는 대상. (PRD §22.1)
    private static final double W_TAG = 0.45;
    private static final double W_COLOR = 0.35;
    private static final double W_FORMALITY = 0.20;

    private static final Map<String, String> COLOR_FAMILY = Map.ofEntries(
            Map.entry("black", "black"),
            Map.entry("white", "white"),
            Map.entry("ivory", "white"),
            Map.entry("cream", "white"),
            Map.entry("grey", "grey"),
            Map.entry("gray", "grey"),
            Map.entry("silver", "grey"),
            Map.entry("charcoal", "grey"),
            Map.entry("beige", "sand"),
            Map.entry("sand", "sand"),
            Map.entry("cognac", "cognac"),
            Map.entry("brown", "cognac"),
            Map.entry("tan", "cognac"),
            Map.entry("camel", "cognac"),
            Map.entry("gold", "gold"),
            Map.entry("red", "red"),
            Map.entry("ruby", "red"),
            Map.entry("burgundy", "red"),
            Map.entry("navy", "navy"),
            Map.entry("blue", "navy"),
            Map.entry("denim", "navy"),
            Map.entry("green", "green"),
            Map.entry("forest", "green"),
            Map.entry("olive", "green"),
            Map.entry("khaki", "green"),
            Map.entry("moss", "green"),
            // MCM 시즌 컬러
            Map.entry("cinnamon", "cinnamon"),
            Map.entry("pink", "pink"),
            Map.entry("rose", "pink"),
            Map.entry("multi", "multi")
    );

    /** 무채색 계열. 이 팔레트에는 포인트 컬러가 강하게 붙는다. */
    private static final Set<String> NEUTRAL_FAMILIES = Set.of("black", "white", "grey", "sand");

    /** 포인트로 쓰일 수 있는 강한 색. */
    private static final Set<String> ACCENT_FAMILIES =
            Set.of("cognac", "gold", "red", "green", "navy", "cinnamon", "pink", "multi");

    private final List<Product> products;
    private final Map<String, Product> byId;

    public CatalogService(ObjectMapper objectMapper) {
        this.products = load(objectMapper);
        var index = new LinkedHashMap<String, Product>();
        for (Product p : products) {
            index.put(p.id(), p);
        }
        this.byId = Map.copyOf(index);
        log.info("catalog loaded: {} products ({})", products.size(), countByCategory());
    }

    private List<Product> load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource("catalog.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            JsonNode arr = root.path("products");
            List<Product> list = new ArrayList<>();
            for (JsonNode node : arr) {
                list.add(objectMapper.treeToValue(node, Product.class));
            }
            return List.copyOf(list);
        } catch (Exception e) {
            // Jackson 3 의 JacksonException 은 unchecked 이므로 IOException 만 잡으면 새어나간다.
            throw new IllegalStateException("catalog.json 을 읽을 수 없습니다", e);
        }
    }

    private String countByCategory() {
        StringBuilder sb = new StringBuilder();
        for (String c : CATEGORIES) {
            sb.append(c).append('=').append(byCategory(c).size()).append(' ');
        }
        return sb.toString().trim();
    }

    public List<Product> all() {
        return products;
    }

    public Optional<Product> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Product> byCategory(String category) {
        return products.stream().filter(p -> p.category().equals(category)).toList();
    }

    /**
     * 카테고리별 후보군을 점수 순으로 추린다.
     *
     * @return category -> 후보 리스트 (점수 내림차순)
     */
    public Map<String, List<Product>> prefilter(Outfit outfit) {
        Map<String, List<Product>> result = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            List<Product> ranked = byCategory(category).stream()
                    .sorted(Comparator.comparingDouble((Product p) -> score(p, outfit)).reversed())
                    .limit(CANDIDATE_SIZE)
                    .toList();
            result.put(category, ranked);
        }
        return result;
    }

    /** 프리필터 점수. 0.0 ~ 1.0 */
    public double score(Product product, Outfit outfit) {
        return W_TAG * tagScore(product, outfit)
                + W_COLOR * colorScore(product, outfit)
                + W_FORMALITY * formalityScore(product, outfit);
    }

    private double tagScore(Product product, Outfit outfit) {
        List<String> moodTags = outfit.moodTags() == null ? List.of() : outfit.moodTags();
        if (moodTags.isEmpty() || product.styleTags() == null) {
            return 0.0;
        }
        Set<String> mood = new HashSet<>(moodTags.stream().map(CatalogService::norm).toList());
        long overlap = product.styleTags().stream().map(CatalogService::norm).filter(mood::contains).count();
        // 3개 이상 겹치면 만점 취급 — 태그가 많은 제품이 무조건 유리해지는 것을 막는다.
        return Math.min(1.0, overlap / 3.0);
    }

    /**
     * 색상 조화도.
     * 톤인톤(같은 계열)보다 <b>무채색 착장 + 포인트 컬러</b> 조합을 더 높게 본다.
     * 올블랙에 코냑 하나 얹는 것이 올블랙에 블랙을 더하는 것보다 스타일링이기 때문.
     */
    private double colorScore(Product product, Outfit outfit) {
        List<String> palette = outfit.palette() == null ? List.of() : outfit.palette();
        if (palette.isEmpty() || product.colors() == null || product.colors().isEmpty()) {
            return 0.4;
        }
        Set<String> outfitFamilies = new HashSet<>(palette.stream().map(CatalogService::family).toList());
        Set<String> productFamilies = new HashSet<>(product.colors().stream().map(CatalogService::family).toList());

        boolean outfitIsNeutral = NEUTRAL_FAMILIES.containsAll(outfitFamilies);
        boolean productHasAccent = productFamilies.stream().anyMatch(ACCENT_FAMILIES::contains);
        boolean shares = productFamilies.stream().anyMatch(outfitFamilies::contains);

        if (outfitIsNeutral && productHasAccent) {
            return 1.0;   // 포인트
        }
        if (shares) {
            return 0.75;  // 톤인톤
        }
        if (NEUTRAL_FAMILIES.containsAll(productFamilies)) {
            return 0.55;  // 무난
        }
        return 0.3;       // 충돌 가능
    }

    private double formalityScore(Product product, Outfit outfit) {
        return 1.0 - Math.min(1.0, Math.abs(product.formality() - outfit.formality()));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** 긴 키부터 매칭해야 "light blue" 가 "blue" 보다 먼저 걸리는 식의 오분류를 피한다. */
    private static final List<String> COLOR_KEYS_BY_LENGTH = COLOR_FAMILY.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    /**
     * 색 이름 → 계열.
     * <p>
     * LLM 은 "light blue", "off white" 처럼 수식어가 붙은 색을 뱉는다. 사전에 정확히
     * 없으면 부분 문자열로 한 번 더 찾아 흡수한다 — 못 찾으면 조화도 점수가
     * 통째로 무의미해지기 때문이다.
     */
    private static String family(String color) {
        String n = norm(color);
        String direct = COLOR_FAMILY.get(n);
        if (direct != null) {
            return direct;
        }
        for (String key : COLOR_KEYS_BY_LENGTH) {
            if (n.contains(key)) {
                return COLOR_FAMILY.get(key);
            }
        }
        return n;
    }
}
