package com.example.back.service;

import com.example.back.domain.Session;
import com.example.back.dto.ApiPayloads.AnalyzeResponse;
import com.example.back.dto.ApiPayloads.RecommendResponse;
import com.example.back.dto.CategoryRecommendation;
import com.example.back.dto.MoodAnalysis;
import com.example.back.dto.MusicTrack;
import com.example.back.dto.Outfit;
import com.example.back.dto.Product;
import com.example.back.dto.RecommendedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 1차 분석 → 2차 추천 파이프라인 오케스트레이션. (PRD §12.1) */
@Service
public class MirrorService {

    private static final Logger log = LoggerFactory.getLogger(MirrorService.class);

    private static final int ITEMS_PER_CATEGORY = 3;

    private final LlmClient llm;
    private final CatalogService catalog;
    private final JamendoClient jamendo;

    public MirrorService(LlmClient llm, CatalogService catalog,
                         JamendoClient jamendo) {
        this.llm = llm;
        this.catalog = catalog;
        this.jamendo = jamendo;
    }

    // ------------------------------------------------------------------ 1차

    /**
     * 분석용 스틸컷을 받아 조명·음향 연출을 만든다.
     * <p>
     * 세션은 이미 열려 있는 상태로 들어온다. 기존에는 이 메서드가 sessionId 를 발급했지만,
     * 직원 알림에 어느 미러인지가 필요해 세션 생성을 앞으로 당겼다.
     * <p>
     * 상태 전이는 {@code startAnalysis()} → {@code applyMood()} 두 번 일어나며,
     * 저장은 호출자(MirrorController)가 담당한다.
     */
    public AnalyzeResponse analyze(Session session, String dataUrl) {
        // 디코딩을 상태 전이보다 먼저 한다. 순서가 반대면 깨진 이미지를 받은 세션이
        // ANALYZING 에 갇힌다 — 거기서 갈 수 있는 곳은 MOOD_ACTIVE 뿐이라
        // 재촬영 요청이 영원히 409 로 막히고 고객은 처음부터 다시 해야 한다.
        byte[] image = decode(dataUrl);

        session.startAnalysis();

        Optional<MoodAnalysis> fromLlm = llm.analyzeMood(image);
        boolean fallback = fromLlm.isEmpty();
        MoodAnalysis analysis = fromLlm.orElseGet(() -> FallbackPresets.pick(image));

        // 고객 화면에 노출되는 유일한 LLM 텍스트이므로 제품 언급이 섞이지 않았는지 확인한다.
        analysis = stripProductMentions(analysis);

        // 음원은 조명과 동시에 시작해야 하므로 이 응답에 실어 보낸다.
        // 실패해도 track=null 로 내려보내고 프론트가 절차적 앰비언스로 받는다.
        MusicTrack track = selectTrack(analysis);

        // image 참조를 여기서 끊는다. 세션에는 분석 결과만 들어간다.
        session.applyMood(analysis, track, fallback);

        String note = fallback
                ? (llm.isMock() ? "mock 모드 — 폴백 프리셋 사용 중 (API 키 미설정)" : "LLM 분석 실패 — 폴백 프리셋 적용")
                : null;

        log.info("analyze done sessionId={} concept='{}' fallback={} track={}",
                session.id(), analysis.conceptName(), fallback,
                track == null ? "none(synth)" : track.title());

        return new AnalyzeResponse(session.id(), analysis, track, fallback, note);
    }

    /**
     * 후보 검색 → GPT 선곡 → 서버 검증.
     * <p>
     * GPT 선곡이 실패하면 첫 후보(= Jamendo 관련성 순 1위)를 쓴다. 이때 reason 은
     * null 이라 화면에서 "AI 선곡" 표시가 붙지 않는다 — 실제로 AI 가 고르지 않았기 때문.
     */
    private MusicTrack selectTrack(MoodAnalysis analysis) {
        List<JamendoClient.Candidate> candidates = jamendo.searchCandidates(analysis.music());
        if (candidates.isEmpty()) {
            return null;
        }

        Optional<LlmClient.TrackPick> pick =
                llm.pickTrack(analysis.outfit(), analysis.mood(), analysis.conceptName(), candidates);

        JamendoClient.Candidate chosen = pick
                .flatMap(p -> candidates.stream().filter(c -> c.id().equals(p.trackId())).findFirst())
                .orElse(candidates.get(0));

        String reason = pick.filter(p -> p.trackId().equals(chosen.id()))
                .map(LlmClient.TrackPick::reason)
                .orElse(null);

        log.info("선곡: '{}' / {} ({}, 상업이용 {})",
                chosen.title(), chosen.artist(),
                reason == null ? "폴백 — 검색 1위" : "AI 선곡",
                chosen.commercialOk() ? "가능" : "불가(NC)");

        return new MusicTrack(
                chosen.id(), chosen.title(), chosen.artist(), chosen.audioUrl(),
                chosen.durationSec(), chosen.shareUrl(), chosen.license(), "jamendo",
                reason, chosen.commercialOk()
        );
    }

    // ------------------------------------------------------------------ 2차

    /**
     * 제품 추천. <b>직원 화면 전용이며 고객용 컨트롤러에서 호출하지 않는다.</b>
     * <p>
     * 호출할 때마다 LLM 랭킹이 새로 도므로 직원 쪽에서 결과를 캐시해야 한다.
     * 무료 등급은 분당 3회 제한이라 폴링하면 바로 429 가 나고, 폴백이 조용히
     * 받아버려 화면상으로는 정상처럼 보인다.
     */
    public Optional<RecommendResponse> recommend(Session session) {
        return session.analysis().map(analysis -> {
            String sessionId = session.id();
            Outfit outfit = analysis.outfit();

            Map<String, List<Product>> candidates = catalog.prefilter(outfit);
            Optional<LlmClient.RankResult> ranked = llm.rankProducts(outfit, analysis.mood(), candidates);

            List<CategoryRecommendation> result = ranked
                    .map(r -> merge(r, candidates))
                    .orElseGet(() -> fromPrefilterOnly(candidates));

            boolean fallback = ranked.isEmpty();
            String stylingNote = ranked.map(LlmClient.RankResult::stylingNote)
                    .filter(s -> !s.isBlank())
                    .orElse("착장의 색 계열에 맞춰 프리필터 점수 상위 항목을 제시합니다.");

            String note = fallback
                    ? (llm.isMock() ? "mock 모드 — 프리필터 점수만으로 선정" : "LLM 랭킹 실패 — 프리필터 상위로 대체")
                    : null;

            log.info("recommend done sessionId={} fallback={}", sessionId, fallback);
            return new RecommendResponse(sessionId, result, stylingNote, fallback, note);
        });
    }

    /**
     * LLM 이 고른 product_id 를 카탈로그와 재대조한다 — 할루시네이션 2차 방어. (PRD §12.2)
     * <p>
     * 검증하는 것:
     * 1. 카탈로그에 실존하는 ID 인가
     * 2. 선언한 카테고리와 제품의 실제 카테고리가 일치하는가
     * 3. 같은 제품이 중복되지 않았는가
     * <p>
     * 3개를 못 채우면 프리필터 상위 항목으로 자리를 메운다.
     */
    private List<CategoryRecommendation> merge(LlmClient.RankResult ranked,
                                               Map<String, List<Product>> candidates) {
        List<CategoryRecommendation> out = new ArrayList<>();

        for (String category : CatalogService.CATEGORIES) {
            Set<String> used = new LinkedHashSet<>();
            Set<String> usedNames = new LinkedHashSet<>();
            List<RecommendedItem> items = new ArrayList<>();

            List<LlmClient.RawPick> picks = ranked.picks().stream()
                    .filter(p -> category.equals(p.category()))
                    .sorted((a, b) -> Integer.compare(a.rank(), b.rank()))
                    .toList();

            for (LlmClient.RawPick pick : picks) {
                if (items.size() >= ITEMS_PER_CATEGORY) {
                    break;
                }
                Optional<Product> product = catalog.find(pick.productId());
                if (product.isEmpty()) {
                    log.warn("존재하지 않는 product_id 폐기: {}", pick.productId());
                    continue;
                }
                if (!category.equals(product.get().category())) {
                    log.warn("카테고리 불일치 폐기: {} (선언={}, 실제={})",
                            pick.productId(), category, product.get().category());
                    continue;
                }
                if (!used.add(pick.productId())) {
                    continue;
                }
                if (!usedNames.add(displayKey(product.get()))) {
                    log.debug("같은 제품의 다른 컬러라 건너뜀: {}", product.get().name());
                    continue;
                }
                items.add(new RecommendedItem(items.size() + 1, pick.productId(), pick.reason(), product.get()));
            }

            fillFromPrefilter(items, used, usedNames, candidates.getOrDefault(category, List.of()));
            out.add(new CategoryRecommendation(category, items));
        }
        return out;
    }

    /**
     * 표시 기준 중복 판정 키.
     * <p>
     * 카탈로그에는 같은 제품이 색상별로 여러 SKU 로 들어 있어서, ID 로만 중복을 막으면
     * 직원 카드 3칸 중 2칸이 같은 물건이 된다. 게다가 MCM 표기가
     * "M-Art 비세토스 1인치 벨트" / "M-Art 비세토스 벨트 1인치" 처럼 어순만 다른 경우가
     * 있어 단순 이름 비교로는 못 잡는다. 그래서 공백으로 쪼개 정렬한 값을 키로 쓴다.
     */
    private String displayKey(Product product) {
        String[] tokens = product.name().toLowerCase(Locale.ROOT).trim().split("\\s+");
        Arrays.sort(tokens);
        return String.join(" ", tokens);
    }

    /** LLM 실패 시 경로. 프리필터 점수 상위 3개 + 템플릿 사유. */
    private List<CategoryRecommendation> fromPrefilterOnly(Map<String, List<Product>> candidates) {
        List<CategoryRecommendation> out = new ArrayList<>();
        for (String category : CatalogService.CATEGORIES) {
            List<RecommendedItem> items = new ArrayList<>();
            fillFromPrefilter(items, new LinkedHashSet<>(), new LinkedHashSet<>(),
                    candidates.getOrDefault(category, List.of()));
            out.add(new CategoryRecommendation(category, items));
        }
        return out;
    }

    private void fillFromPrefilter(List<RecommendedItem> items, Set<String> used,
                                   Set<String> usedNames, List<Product> pool) {
        for (Product p : pool) {
            if (items.size() >= ITEMS_PER_CATEGORY) {
                return;
            }
            if (!used.add(p.id())) {
                continue;
            }
            if (!usedNames.add(displayKey(p))) {
                continue;
            }
            items.add(new RecommendedItem(items.size() + 1, p.id(), templateReason(p), p));
        }
    }

    private String templateReason(Product p) {
        return "착장 톤과 어울리는 " + String.join("·", p.colors()) + " 계열";
    }

    /**
     * styleComment 에 제품명·카테고리가 섞였는지 검사한다 — PRD §1.1 원칙의 렌더링 단계 방어.
     * 프롬프트로 금지해도 새어나올 수 있으므로 서버에서 한 번 더 막는다.
     */
    private MoodAnalysis stripProductMentions(MoodAnalysis analysis) {
        String comment = analysis.styleComment();
        if (comment == null || comment.isBlank()) {
            return analysis;
        }
        String lower = comment.toLowerCase(Locale.ROOT);

        boolean leaked = List.of("가방", "지갑", "벨트", "백팩", "크로스백", "카드홀더", "구매", "추천").stream()
                .anyMatch(comment::contains)
                || catalog.all().stream().anyMatch(p -> lower.contains(p.name().toLowerCase(Locale.ROOT)));

        if (!leaked) {
            return analysis;
        }
        log.warn("styleComment 에 제품 언급이 감지되어 차단했습니다: {}", comment);
        return new MoodAnalysis(
                analysis.outfit(), analysis.mood(), analysis.conceptName(),
                analysis.lighting(), analysis.music(),
                null
        );
    }

    /** data URL → 원본 바이트. 디스크를 거치지 않는다. (PRD §16.3) */
    private byte[] decode(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) {
            throw new IllegalArgumentException("image 가 비어 있습니다");
        }
        int comma = dataUrl.indexOf(',');
        String base64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (bytes.length == 0) {
                throw new IllegalArgumentException("이미지 디코딩 결과가 비어 있습니다");
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            // 예외 메시지에 base64 원문이 실리지 않도록 메시지를 새로 만든다. (PRD §16.3)
            throw new IllegalArgumentException("이미지 base64 디코딩 실패");
        }
    }
}
