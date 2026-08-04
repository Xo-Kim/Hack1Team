package com.example.back.service;

import com.example.back.config.MoodMirrorProperties;
import com.example.back.dto.MusicSpec;
import com.example.back.dto.MusicTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Jamendo(CC 라이선스 음원) 검색 클라이언트.
 * <p>
 * 전곡 재생이 가능하고 라이선스가 명확해서, 발표 녹화본 저작권 문제가 생기지 않는다.
 * (PRD §19 의 저작권 리스크 대응)
 * <p>
 * 실패는 전부 {@link Optional#empty()} 로 흡수한다 — 음원을 못 찾는다고 연출이
 * 멈추면 안 된다. 프론트가 절차적 앰비언스로 폴백한다.
 * <p>
 * <b>실제 매장 도입 시 검토할 것</b>: 지금 돌아오는 곡은 CC BY-NC-ND 처럼
 * 비상업(NC) 조건이 붙은 것이 섞인다. 해커톤 시연은 문제없지만 매장 영업은
 * 상업적 이용이다. Jamendo API 에 이를 거르는 파라미터가 있다.
 * <ul>
 *   <li>{@code probackground=true} — 매장 배경음악 상업 프로그램 등록 곡만</li>
 *   <li>{@code ccnc=false} — 비상업 조건이 붙은 곡 제외</li>
 * </ul>
 */
@Component
public class JamendoClient {

    private static final Logger log = LoggerFactory.getLogger(JamendoClient.class);

    private final MoodMirrorProperties.Music.Jamendo cfg;
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

    public JamendoClient(MoodMirrorProperties props) {
        this.cfg = props.music() == null || props.music().jamendo() == null
                ? new MoodMirrorProperties.Music.Jamendo("", "https://api.jamendo.com/v3.0", 4)
                : props.music().jamendo();

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(1, cfg.timeoutSeconds())));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, cfg.timeoutSeconds())));

        this.http = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(factory)
                .build();

        if (!cfg.enabled()) {
            log.warn("JAMENDO_CLIENT_ID 가 비어 있습니다 — 음원 검색을 건너뛰고 절차적 앰비언스로 재생합니다.");
        }
    }

    public boolean isEnabled() {
        return cfg.enabled();
    }

    /**
     * GPT 선곡에 넘길 후보 1건.
     * <p>
     * 응답 원본에는 {@code waveform}(숫자 700여 개)처럼 프롬프트에 넣으면 안 되는
     * 필드가 섞여 있다. 여기 담긴 것만 GPT 에게 보낸다.
     */
    public record Candidate(
            String id,
            String title,
            String artist,
            int durationSec,
            String audioUrl,
            String shareUrl,
            String license,
            List<String> genres,
            List<String> instruments,
            List<String> moodTags,
            String acousticElectric,
            String speed,
            boolean commercialOk
    ) {
        /** 프롬프트 한 줄 표현. 곡당 40토큰 남짓. */
        String toPromptLine(String label) {
            return "- " + label
                    + " | " + title + " / " + artist
                    + " | " + (durationSec / 60) + ":" + String.format("%02d", durationSec % 60)
                    + " | genres=" + joinOr(genres, "unknown")
                    + " instruments=" + joinOr(instruments, "unknown")
                    + " mood=" + joinOr(moodTags, "unknown")
                    + " " + (acousticElectric.isBlank() ? "" : acousticElectric)
                    + " speed=" + (speed.isBlank() ? "unknown" : speed);
        }

        private static String joinOr(List<String> v, String fallback) {
            return v == null || v.isEmpty() ? fallback : String.join("/", v);
        }
    }

    /**
     * 무드 스펙으로 곡을 찾는다.
     * <p>
     * 태그를 좁게 걸면 결과가 0건이 되기 쉬우므로 3단계로 넓혀가며 재시도한다.
     * 마지막 단계까지 실패하면 폴백에 맡긴다.
     */
    public List<Candidate> searchCandidates(MusicSpec spec) {
        if (!cfg.enabled()) {
            return List.of();
        }

        List<String> tags = normalizeTags(spec);
        String speed = speedOf(spec.energy());
        List<String> genreOnly = normalizeWords(spec.genreHint());

        /*
         * 넓혀가며 재시도한다. 태그만 넓히고 speed 를 계속 걸어두면,
         * speed 가 결과를 0건으로 만드는 경우 태그를 아무리 풀어도 소용이 없다.
         * 실제로 [boombap+bass+groove+hiphop] + speed=high 가 0건이라
         * 마지막 ambient 까지 떨어지는 일이 있었다. 그래서 2단계부터 speed 를 뗀다.
         */
        List<Attempt> attempts = new ArrayList<>();
        if (!tags.isEmpty()) {
            attempts.add(new Attempt(tags, speed));      // 1. 전체 태그 + 에너지
            attempts.add(new Attempt(tags, null));       // 2. 전체 태그만
        }
        if (!genreOnly.isEmpty() && !genreOnly.equals(tags)) {
            attempts.add(new Attempt(genreOnly, null));  // 3. 장르만
        }
        attempts.add(new Attempt(List.of("groove"), null)); // 4. 최후 — 무드보다 브랜드 톤 우선

        for (Attempt attempt : attempts) {
            List<Candidate> found = query(attempt.tags(), attempt.speed());
            if (!found.isEmpty()) {
                log.info("jamendo hit: tags={} speed={} → 후보 {}곡 (상업이용 가능 {}곡)",
                        attempt.tags(), attempt.speed() == null ? "(제한없음)" : attempt.speed(),
                        found.size(), found.stream().filter(Candidate::commercialOk).count());
                return found;
            }
        }

        log.warn("jamendo 검색 결과 없음 (tags={}) — 절차적 앰비언스로 폴백", tags);
        return List.of();
    }

    private record Attempt(List<String> tags, String speed) {
    }

    private List<Candidate> query(List<String> tags, String speed) {
        try {
            // Jamendo 는 다중 태그를 '+' 로 구분한다. 태그는 이미 [a-z0-9] 로 정규화되어
            // 있으므로 인코딩 없이 그대로 이어붙여도 안전하다.
            // order 가 아니라 boost 를 쓴다.
            // 공식 문서: 검색(fuzzytags)에 order 를 지정하면 검색 관련성 순서가 통째로
            // 사라지고 인기순으로만 정렬된다. 그러면 무드 매칭이 무의미해진다.
            // boost 는 관련성을 유지한 채 인기도를 가중치로만 반영한다.
            String uri = "/tracks/"
                    + "?client_id=" + cfg.clientId()
                    + "&format=json"
                    + "&limit=10"   // GPT 가 실제로 고를 수 있을 만큼은 준다
                    + "&fuzzytags=" + String.join("+", tags)
                    + (speed == null ? "" : "&speed=" + speed)
                    + "&audioformat=mp32"
                    + "&include=musicinfo+licenses"
                    + "&boost=popularity_month";

            String raw = http.get().uri(uri).retrieve().body(String.class);
            if (raw == null) {
                return List.of();
            }

            JsonNode root = json.readTree(raw);
            String status = root.path("headers").path("status").asString("");
            if (!"success".equals(status)) {
                log.warn("jamendo 응답 실패: {}", root.path("headers").path("error_message").asString("unknown"));
                return List.of();
            }

            List<Candidate> out = new ArrayList<>();
            for (JsonNode t : root.path("results")) {
                String audio = t.path("audio").asString("");
                if (audio.isBlank()) {
                    continue;   // 스트리밍 URL 이 없는 항목은 건너뛴다
                }
                JsonNode info = t.path("musicinfo");
                JsonNode infoTags = info.path("tags");

                out.add(new Candidate(
                        t.path("id").asString(""),
                        t.path("name").asString("Untitled"),
                        t.path("artist_name").asString("Unknown"),
                        t.path("duration").asInt(0),
                        audio,
                        t.path("shareurl").asString(""),
                        t.path("license_ccurl").asString(""),
                        strings(infoTags.path("genres")),
                        strings(infoTags.path("instruments")),
                        strings(infoTags.path("vartags")),
                        info.path("acousticelectric").asString(""),
                        info.path("speed").asString(""),
                        // licenses.ccnc 가 "true" 면 비상업 조건이 붙은 곡이다.
                        !"true".equals(t.path("licenses").path("ccnc").asString("true"))
                ));
            }
            return out;
        } catch (Exception e) {
            log.warn("jamendo 호출 실패 — 폴백으로 전환: {}", e.toString());
            return List.of();
        }
    }

    private static List<String> strings(JsonNode arr) {
        if (!arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String s = n.asString("");
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    /** energy(0~1) → Jamendo 의 speed 파라미터. */
    private static String speedOf(double energy) {
        if (energy < 0.2) return "verylow";
        if (energy < 0.4) return "low";
        if (energy < 0.6) return "medium";
        if (energy < 0.8) return "high";
        return "veryhigh";
    }

    /**
     * queryTags + genreHint 를 검색 가능한 단어로 정리한다.
     * <p>
     * LLM 은 "dark r&b", "night drive" 같은 구를 뱉는데, 그대로 URL 에 넣으면
     * {@code &} 가 쿼리 파라미터를 깨뜨린다. 알파벳/숫자만 남기고 단어로 쪼갠 뒤
     * 2글자 이하는 버린다 ("r", "b" 같은 조각이 검색을 망친다).
     */
    private static List<String> normalizeTags(MusicSpec spec) {
        Set<String> out = new LinkedHashSet<>();
        List<String> queryTags = spec.queryTags() == null ? List.of() : spec.queryTags();
        for (String s : queryTags) {
            out.addAll(normalizeWords(s));
        }
        out.addAll(normalizeWords(spec.genreHint()));
        return out.stream().limit(4).toList();
    }

    private static List<String> normalizeWords(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(Objects::nonNull)
                .filter(w -> w.length() >= 3)
                .distinct()
                .toList();
    }
}
