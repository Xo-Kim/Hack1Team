package com.example.back.service;

import com.example.back.config.MoodMirrorProperties;
import com.example.back.dto.MoodAnalysis;
import com.example.back.dto.Outfit;
import com.example.back.dto.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OpenAI 호환 Chat Completions 클라이언트.
 * <p>
 * base-url / 모델명이 설정으로 빠져 있으므로 OpenAI 호환 엔드포인트라면
 * 그대로 교체 가능하다. 다른 제공사로 옮길 때는 이 클래스만 갈아끼우면 된다.
 * <p>
 * 실패 시 예외를 던지지 않고 {@link Optional#empty()} 를 돌려준다 — 데모가 죽는 것보다
 * 폴백 프리셋으로 넘어가는 편이 항상 낫다. (PRD §18)
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final MoodMirrorProperties.Llm cfg;
    private final RestClient http;

    /** LLM 페이로드 전용 매퍼. 스펙이 snake_case 라 프론트 응답용 매퍼와 분리한다. */
    private final ObjectMapper snake = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private final ObjectMapper plain = new ObjectMapper();

    public LlmClient(MoodMirrorProperties props) {
        this.cfg = props.llm();

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(cfg.connectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(cfg.readTimeoutSeconds()));

        this.http = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(factory)
                .build();

        if (cfg.mock()) {
            log.warn("LLM API key 가 비어 있습니다 — mock 모드로 동작합니다 (폴백 프리셋 사용).");
        }
    }

    public boolean isMock() {
        return cfg.mock();
    }

    // ------------------------------------------------------------------ 1차: Vision

    public Optional<MoodAnalysis> analyzeMood(byte[] jpeg) {
        if (cfg.mock()) {
            return Optional.empty();
        }
        try {
            String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);

            ObjectNode body = plain.createObjectNode();
            body.put("model", cfg.visionModel());
            body.put("temperature", 0.3);

            ArrayNode messages = body.putArray("messages");
            messages.addObject()
                    .put("role", "system")
                    .put("content", visionSystemPrompt());

            ObjectNode user = messages.addObject();
            user.put("role", "user");
            ArrayNode content = user.putArray("content");
            content.addObject()
                    .put("type", "text")
                    .put("text", "이 사람의 착장과 분위기를 읽고 조명·음향 스펙을 만들어 주세요.");
            content.addObject()
                    .put("type", "image_url")
                    .putObject("image_url")
                    .put("url", dataUrl)
                    .put("detail", "low");

            body.set("response_format", responseFormat("mood_analysis", plain.readTree(MOOD_SCHEMA)));

            JsonNode parsed = call(body);
            if (parsed == null) {
                return Optional.empty();
            }
            return Optional.of(clamp(snake.treeToValue(parsed, MoodAnalysis.class)));
        } catch (Exception e) {
            warnFailure("1차 Vision 분석", e);
            return Optional.empty();
        }
    }

    /**
     * 429 는 다른 실패와 구분해서 알려준다.
     * <p>
     * 무료 등급의 RPM 이 낮으면 한 세션(호출 2회)만으로도 한도에 닿는다. 그런데
     * 폴백 프리셋이 워낙 매끄럽게 받아버려서, 화면만 보면 정상 동작하는 것처럼 보인다.
     * 데모 중에 이걸 모르고 넘어가는 것이 가장 위험하다.
     */
    private void warnFailure(String stage, Exception e) {
        if (e instanceof HttpClientErrorException.TooManyRequests) {
            log.warn("{} 실패 — [RATE LIMIT] OpenAI 분당 요청 한도 초과. "
                    + "AI 가 아니라 폴백 프리셋이 적용됩니다. "
                    + "결제 수단을 등록해 한도를 올리거나 호출 간격을 두십시오.", stage);
            return;
        }
        log.warn("{} 실패 — 폴백으로 전환: {}", stage, e.toString());
    }

    // ------------------------------------------------------------------ 2차: 추천 랭킹

    public record RawPick(String category, int rank, String productId, String reason) {
    }

    public record RankResult(List<RawPick> picks, String stylingNote) {
    }

    public Optional<RankResult> rankProducts(Outfit outfit, String mood, Map<String, List<Product>> candidates) {
        if (cfg.mock()) {
            return Optional.empty();
        }
        try {
            Set<String> validIds = new LinkedHashSet<>();
            candidates.values().forEach(list -> list.forEach(p -> validIds.add(p.id())));
            if (validIds.isEmpty()) {
                return Optional.empty();
            }

            ObjectNode body = plain.createObjectNode();
            body.put("model", cfg.textModel());
            body.put("temperature", 0.4);

            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", rankSystemPrompt());
            messages.addObject().put("role", "user").put("content", rankUserPrompt(outfit, mood, candidates));

            body.set("response_format", responseFormat("recommendations", rankSchema(validIds)));

            JsonNode parsed = call(body);
            if (parsed == null) {
                return Optional.empty();
            }

            List<RawPick> picks = new ArrayList<>();
            for (JsonNode cat : parsed.path("recommendations")) {
                String category = cat.path("category").asString("");
                for (JsonNode item : cat.path("items")) {
                    picks.add(new RawPick(
                            category,
                            item.path("rank").asInt(0),
                            item.path("product_id").asString(""),
                            item.path("reason").asString("")
                    ));
                }
            }
            return Optional.of(new RankResult(picks, parsed.path("styling_note").asString("")));
        } catch (Exception e) {
            warnFailure("2차 추천 랭킹", e);
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ 선곡

    public record TrackPick(String trackId, String reason) {
    }

    /**
     * 후보 곡 중 하나를 GPT 가 직접 고른다.
     * <p>
     * 이전에는 GPT 가 검색어만 만들고 실제 곡은 Jamendo 검색 순서가 정했다.
     * 조명(값 직접 생성)·제품(ID 직접 선택)과 달리 음향만 GPT 에게 결정권이 없어
     * 일관성이 없었다. 이제 제품 추천과 같은 패턴이다 —
     * 후보 제공 → GPT 선택 → 서버 검증.
     */
    public Optional<TrackPick> pickTrack(Outfit outfit, String mood, String conceptName,
                                         List<JamendoClient.Candidate> candidates) {
        if (cfg.mock() || candidates.isEmpty()) {
            return Optional.empty();
        }
        try {
            // 후보를 T1, T2... 로 라벨링한다. Jamendo 의 원본 id 를 그대로 쓰면
            // 숫자 나열이라 모델이 헷갈리고 enum 도 길어진다.
            List<String> labels = new ArrayList<>();
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                String label = "T" + (i + 1);
                labels.add(label);
                list.append(candidates.get(i).toPromptLine(label)).append('\n');
            }

            String user = "[고객 착장]\n"
                    + "- 컨셉: " + conceptName + "\n"
                    + "- 무드: " + mood + "\n"
                    + "- 스타일: " + outfit.style() + "\n"
                    + "- 팔레트: " + String.join(", ", outfit.palette()) + "\n"
                    + "- 포멀리티: " + String.format("%.2f", outfit.formality()) + "\n"
                    + "- 태그: " + String.join(", ", outfit.moodTags()) + "\n\n"
                    + "[후보 곡]\n" + list;

            ObjectNode body = plain.createObjectNode();
            body.put("model", cfg.textModel());
            body.put("temperature", 0.4);

            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", """
                    당신은 MCM 매장의 음악 디렉터입니다.
                    고객의 착장 분석 결과와 후보 곡 목록을 받아, 그 순간에 가장 어울리는 곡 하나를 고릅니다.

                    규칙:
                    - 반드시 주어진 후보 라벨(T1, T2 ...) 중에서만 고릅니다.
                    - MCM 은 힙합·스트릿 문화에 뿌리를 둔 브랜드입니다.
                      같은 조건이면 리듬과 그루브가 있는 곡을 우선합니다.
                    - 명상·뉴에이지처럼 들리는 곡은 아주 조용한 착장이 아닌 한 피합니다.
                    - 당신은 곡을 들을 수 없고 메타데이터만 봅니다. 그러므로 태그뿐 아니라
                      <b>곡 제목이 주는 인상</b>도 함께 고려하십시오.
                      제목이 "Angry" 인 곡을 고르고 reason 에 "차분하다"고 쓰면 안 됩니다.
                      제목과 reason 이 어긋나면 다른 곡을 고르십시오.
                    - reason 은 한국어 30자 이내 한 문장. 왜 이 착장에 이 곡인지만 적습니다.
                    """);
            messages.addObject().put("role", "user").put("content", user);

            body.set("response_format", responseFormat("track_pick", trackSchema(labels)));

            JsonNode parsed = call(body);
            if (parsed == null) {
                return Optional.empty();
            }
            String label = parsed.path("track_id").asString("");
            int idx = labels.indexOf(label);
            if (idx < 0) {
                log.warn("후보에 없는 track_id 폐기: {}", label);
                return Optional.empty();
            }
            return Optional.of(new TrackPick(candidates.get(idx).id(),
                    parsed.path("reason").asString("")));
        } catch (Exception e) {
            warnFailure("선곡", e);
            return Optional.empty();
        }
    }

    /** 후보 라벨을 enum 으로 강제한다 — product_id 와 같은 1차 방어. */
    private JsonNode trackSchema(List<String> labels) {
        ObjectNode root = plain.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.putArray("required").add("track_id").add("reason");

        ObjectNode props = root.putObject("properties");
        ObjectNode id = props.putObject("track_id");
        id.put("type", "string");
        ArrayNode e = id.putArray("enum");
        labels.forEach(e::add);
        props.putObject("reason").put("type", "string");
        return root;
    }

    // ------------------------------------------------------------------ 공통

    /** 호출 후 message.content 안의 JSON 문자열을 파싱해 돌려준다. 실패 시 null. */
    private JsonNode call(ObjectNode body) {
        String raw = http.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + cfg.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);

        if (raw == null) {
            return null;
        }
        try {
            JsonNode root = plain.readTree(raw);
            JsonNode message = root.path("choices").path(0).path("message");
            if (message.path("refusal").isString()) {
                log.warn("모델이 응답을 거부했습니다: {}", message.path("refusal").asString(""));
                return null;
            }
            String content = message.path("content").asString(null);
            if (content == null || content.isBlank()) {
                return null;
            }
            return plain.readTree(content);
        } catch (Exception e) {
            log.warn("LLM 응답 파싱 실패: {}", e.toString());
            return null;
        }
    }

    private ObjectNode responseFormat(String name, JsonNode schema) {
        ObjectNode rf = plain.createObjectNode();
        rf.put("type", "json_schema");
        ObjectNode js = rf.putObject("json_schema");
        js.put("name", name);
        js.put("strict", true);
        js.set("schema", schema);
        return rf;
    }

    /**
     * 조명·음악 값을 프리셋 범위로 클램핑한다. (PRD §13.3)
     * 모델이 극단값을 내면 화면이 새하얗게 뜨거나 아무것도 안 보이는 연출이 나온다.
     */
    private MoodAnalysis clamp(MoodAnalysis a) {
        var l = a.lighting();
        var m = a.music();
        var o = a.outfit();

        String effect = blankTo(l.effect(), "breathe");
        if (!List.of("static", "breathe", "sweep").contains(effect)) {
            effect = "breathe";
        }

        var lighting = new com.example.back.dto.Lighting(
                // 모델이 concept_name 과 scene_name 에 서로 다른 이름을 넣는 일이 잦다.
                // 한 장면에 이름은 하나여야 하므로 컨셉명으로 통일한다.
                blankTo(a.conceptName(), l.sceneName()),
                hexOr(l.primaryColor(), "#1a1a2e"),
                hexOr(l.accentColor(), "#c8873c"),
                (int) clampD(l.colorTemperatureK(), 1800, 6500),
                clampD(l.brightness(), 0.25, 0.85),
                (int) clampD(l.transitionMs(), 1200, 3500),
                effect
        );

        var music = new com.example.back.dto.MusicSpec(
                m.queryTags() == null ? List.of() : m.queryTags(),
                clampD(m.energy(), 0.0, 1.0),
                blankTo(m.genreHint(), "ambient"),
                blankTo(m.key(), "D"),
                blankTo(m.scale(), "minor"),
                (int) clampD(m.bpm(), 60, 120)
        );

        var outfit = new Outfit(
                o.palette() == null ? List.of() : o.palette(),
                blankTo(o.style(), "unspecified"),
                clampD(o.formality(), 0.0, 1.0),
                o.moodTags() == null ? List.of() : o.moodTags()
        );

        return new MoodAnalysis(outfit, normalizeMood(a.mood(), outfit), a.conceptName(),
                lighting, music, a.styleComment());
    }

    /**
     * mood 는 화면의 좁은 메타 칸에 들어가는 짧은 태그다.
     * 모델이 종종 설명 문장을 통째로 넣는데, 그러면 레이아웃이 깨지고 고객 화면에
     * 영어 문장이 노출된다. 프롬프트로 금지했지만 지켜지지 않을 때를 대비해
     * 여기서 한 번 더 막고, 길면 mood_tags 로 대체한다.
     */
    private static String normalizeMood(String mood, Outfit outfit) {
        String m = mood == null ? "" : mood.trim().toLowerCase(Locale.ROOT);
        boolean tooLong = m.length() > 24 || m.split("\\s+").length > 2;

        if (!m.isBlank() && !tooLong) {
            return m.replace(' ', '-');
        }
        String fromTags = outfit.moodTags().stream().limit(2).collect(Collectors.joining("-"));
        return fromTags.isBlank() ? "unspecified" : fromTags;
    }

    private static double clampD(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String hexOr(String v, String fallback) {
        return v != null && v.matches("#[0-9a-fA-F]{6}") ? v : fallback;
    }

    // ------------------------------------------------------------------ 프롬프트

    private String visionSystemPrompt() {
        return """
                당신은 MCM 매장의 조명·음향 디렉터입니다. 사진 속 인물의 착장과 분위기를 읽고,
                그 순간에 어울리는 조명 씬과 음향 스펙을 만듭니다.

                규칙:
                - 인물이 여럿이면 화면 중앙에 가장 크게 잡힌 한 명만 기준으로 판단합니다.
                - palette 는 소문자 영문 색상 한 단어로만 적습니다 (예: black, cognac, navy, sand).
                  "light blue" 처럼 두 단어로 쓰지 말고 blue 로 적습니다.
                - mood 는 소문자 영문 하이픈 태그 한 개입니다. 두 단어를 넘기지 않습니다
                  (예: confident-night, calm-daylight, warm-retro). 문장으로 쓰지 마십시오.
                - mood_tags 는 주어진 목록에서만 고릅니다. 3~5개.
                - concept_name 은 영문 두 단어. 도시나 시간대를 모티프로 삼습니다
                  (예: Munich Midnight, Seoul Morning, Berlin Blue Hour).
                  MCM 은 뮌헨에서 시작해 서울에 기반을 둔 브랜드이며 스트릿·힙합 문화와 접점이 있습니다.
                - lighting.brightness 는 0.25~0.85, transition_ms 는 1200~3500 안에서 정합니다.
                  어두운 무드라도 인물이 보이지 않을 만큼 낮추지 않습니다.
                음악 규칙 (중요):
                - MCM 의 음악적 뿌리는 80~90년대 힙합·스트릿 문화입니다. 브랜드가 문화적 자본을
                  얻은 곳이 그 씬이고, 지금도 "New School Luxury" 를 표방합니다.
                  따라서 기본값은 hiphop / rnb / soul / funk / electronic 계열입니다.
                - genre_hint 는 착장에서 받은 인상에 따라 <b>매번 다르게</b> 판단합니다.
                  아래는 기준일 뿐이니 그대로 베끼지 말고 실제 착장을 보고 고르십시오.
                    · 어둡고 도시적, 올블랙          → hiphop, triphop, downtempo
                    · 밝고 캐주얼, 파스텔            → funk, soul, pop
                    · 포멀하고 절제된               → downtempo, rnb, jazz
                    · 대담하고 강한 색, 스터드·메탈   → electronic, house, rock
                    · 아주 조용하고 미니멀한 경우만   → ambient, lounge
                - ambient, lounge 를 기본값으로 쓰지 마십시오.
                  스파나 명상 음악처럼 들리면 브랜드와 맞지 않습니다.
                - query_tags 는 영문 <b>한 단어</b> 3개입니다. Jamendo 태그 검색에 쓰이므로
                  두 단어로 쓰면 검색이 실패합니다.
                  1개는 genre_hint 와 같아도 되지만, 나머지 2개는 <b>그 착장에서만 나올 법한
                  구체적인 단어</b>여야 합니다 (악기, 리듬, 질감 등).
                  어느 착장에나 붙는 뻔한 조합("smooth", "chill", "nice" 류)은 피하십시오.
                - energy 는 착장이 주는 인상을 따릅니다. 강하고 대담한 착장은 높게,
                  부드럽고 차분한 착장은 낮게 잡습니다. 캐주얼하다고 무조건 높이지 마십시오.
                - key/scale/bpm 은 실제로 연주 가능한 값이어야 합니다.
                  어두운 무드는 minor/phrygian, 밝은 무드는 major/lydian 쪽을 씁니다.
                  bpm 은 장르에 맞춥니다 (hiphop 80~100, house 118~125, soul 90~110).

                style_comment 규칙 (중요):
                - 한국어 한 문장, 40~60자.
                - 착장의 분위기와 조명 의도만 말합니다.
                - 제품명, 제품 카테고리(가방/지갑/벨트), 구매 권유를 절대 포함하지 않습니다.
                  이 문장은 고객 화면에 그대로 노출되며, 이 서비스는 고객에게 제품을 제안하지 않습니다.
                """;
    }

    private String rankSystemPrompt() {
        return """
                당신은 MCM 매장 직원의 접객을 돕는 스타일링 어시스턴트입니다.
                고객의 착장 분석 결과와 후보 제품 목록을 받아, 카테고리별로 3개를 골라 순위를 매깁니다.

                규칙:
                - 반드시 주어진 후보 목록의 product_id 중에서만 고릅니다. 새로운 제품을 만들어내지 마십시오.
                - 각 카테고리(bag, wallet, belt)마다 정확히 3개, rank 는 1·2·3.
                - 같은 제품을 두 번 넣지 않습니다.
                - reason 은 한국어 40자 이내 한 문장. 직원이 접객 중 흘깃 보고 바로 말할 수 있어야 합니다.
                  "왜 이 착장에 이것인가"만 적습니다. 홍보 문구를 쓰지 마십시오.
                - styling_note 는 세 카테고리를 아우르는 조합 조언 한 문장.

                이 결과는 고객 화면이 아니라 직원 단말에만 표시됩니다.
                """;
    }

    private String rankUserPrompt(Outfit outfit, String mood, Map<String, List<Product>> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("[고객 착장 분석]\n");
        sb.append("- 무드: ").append(mood).append('\n');
        sb.append("- 스타일: ").append(outfit.style()).append('\n');
        sb.append("- 컬러 팔레트: ").append(String.join(", ", outfit.palette())).append('\n');
        sb.append("- 포멀리티: ").append(String.format("%.2f", outfit.formality())).append(" (0=캐주얼, 1=포멀)\n");
        sb.append("- 무드 태그: ").append(String.join(", ", outfit.moodTags())).append("\n\n");

        sb.append("[후보 제품]\n");
        candidates.forEach((category, list) -> {
            sb.append('<').append(category).append(">\n");
            for (Product p : list) {
                sb.append("- ").append(p.id())
                        .append(" | ").append(p.name())
                        .append(" | colors=").append(String.join("/", p.colors()))
                        .append(" | tags=").append(String.join("/", p.styleTags()))
                        .append(" | formality=").append(String.format("%.2f", p.formality()))
                        .append(" | ").append(p.priceKrw()).append("원\n");
            }
            sb.append('\n');
        });
        return sb.toString();
    }

    // ------------------------------------------------------------------ 스키마

    /**
     * product_id 를 후보군 enum 으로 제한한다 — 할루시네이션 1차 방어.
     * 디코딩 단계에서 강제되므로 존재하지 않는 ID 가 나올 수 없다. (PRD §12.2)
     * 서버 측 재검증은 {@link MirrorService} 에서 한 번 더 한다.
     */
    private JsonNode rankSchema(Set<String> validIds) {
        ObjectNode root = plain.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.putArray("required").add("recommendations").add("styling_note");

        ObjectNode props = root.putObject("properties");

        ObjectNode recs = props.putObject("recommendations");
        recs.put("type", "array");

        ObjectNode catItem = recs.putObject("items");
        catItem.put("type", "object");
        catItem.put("additionalProperties", false);
        catItem.putArray("required").add("category").add("items");

        ObjectNode catProps = catItem.putObject("properties");
        ObjectNode category = catProps.putObject("category");
        category.put("type", "string");
        ArrayNode catEnum = category.putArray("enum");
        CatalogService.CATEGORIES.forEach(catEnum::add);

        ObjectNode items = catProps.putObject("items");
        items.put("type", "array");

        ObjectNode item = items.putObject("items");
        item.put("type", "object");
        item.put("additionalProperties", false);
        item.putArray("required").add("rank").add("product_id").add("reason");

        ObjectNode itemProps = item.putObject("properties");
        itemProps.putObject("rank").put("type", "integer");
        ObjectNode pid = itemProps.putObject("product_id");
        pid.put("type", "string");
        ArrayNode idEnum = pid.putArray("enum");
        validIds.forEach(idEnum::add);
        itemProps.putObject("reason").put("type", "string");

        props.putObject("styling_note").put("type", "string");
        return root;
    }

    /**
     * mood_tags 의 enum 은 catalog.json 의 styleTags 어휘와 <b>반드시 동일</b>해야 한다.
     * 어휘가 어긋나면 프리필터의 태그 교집합 점수가 항상 0 이 되어 추천이 무너진다.
     */
    private static final String MOOD_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["outfit", "mood", "concept_name", "lighting", "music", "style_comment"],
              "properties": {
                "outfit": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["palette", "style", "formality", "mood_tags"],
                  "properties": {
                    "palette": { "type": "array", "items": { "type": "string" } },
                    "style": { "type": "string" },
                    "formality": { "type": "number" },
                    "mood_tags": {
                      "type": "array",
                      "items": {
                        "type": "string",
                        "enum": ["street","minimal","classic","bold","elegant","casual","formal",
                                 "sporty","retro","luxe","edgy","romantic","night","day",
                                 "travel","statement","unisex","compact","iconic","monochrome"]
                      }
                    }
                  }
                },
                "mood": { "type": "string" },
                "concept_name": { "type": "string" },
                "lighting": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["scene_name","primary_color","accent_color","color_temperature_k",
                               "brightness","transition_ms","effect"],
                  "properties": {
                    "scene_name": { "type": "string" },
                    "primary_color": { "type": "string" },
                    "accent_color": { "type": "string" },
                    "color_temperature_k": { "type": "integer" },
                    "brightness": { "type": "number" },
                    "transition_ms": { "type": "integer" },
                    "effect": { "type": "string", "enum": ["static", "breathe", "sweep"] }
                  }
                },
                "music": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["query_tags","energy","genre_hint","key","scale","bpm"],
                  "properties": {
                    "query_tags": { "type": "array", "items": { "type": "string" } },
                    "energy": { "type": "number" },
                    "genre_hint": {
                      "type": "string",
                      "enum": ["hiphop","rnb","soul","funk","electronic","house",
                               "downtempo","triphop","jazz","rock","pop","ambient","lounge"]
                    },
                    "key": { "type": "string",
                             "enum": ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"] },
                    "scale": { "type": "string",
                               "enum": ["minor","major","dorian","lydian","phrygian"] },
                    "bpm": { "type": "integer" }
                  }
                },
                "style_comment": { "type": "string" }
              }
            }
            """;
}
