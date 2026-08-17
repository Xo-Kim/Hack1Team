# LLM 입출력 스펙

한 세션에 **3회 호출**한다. 프리필터·검증 로직은 [ARCHITECTURE](ARCHITECTURE.md) 참고.

---

## 1차 호출 — 무드·조명·음악 (Vision)

**입력**: 스틸컷 이미지 1~3장 + 시스템 프롬프트 (MCM 브랜드 무드 가이드 포함)

```json
{
  "outfit": {
    "palette": ["black", "cognac"],
    "style": "street-luxury",
    "formality": 0.6,
    "mood_tags": ["confident", "night", "urban"]
  },
  "mood": "confident-night",
  "concept_name": "Munich Midnight",
  "lighting": {
    "scene_name": "Munich Midnight",
    "primary_color": "#1a1a2e",
    "accent_color": "#c8873c",
    "color_temperature_k": 2700,
    "brightness": 0.55,
    "transition_ms": 2500,
    "effect": "breathe"
  },
  "music": {
    "query_tags": ["dark r&b", "night drive"],
    "energy": 0.6,
    "genre_hint": "hiphop-rnb"
  },
  "style_comment": "시크한 올블랙에 코냑 포인트 — 밤의 뮌헨이 어울리는 룩."
}
```

> **`mood` 는 짧은 라벨이어야 한다.** 제약을 걸지 않으면 모델이 영어 문장을 통째로
> 내놓는다. 프롬프트로 형식을 지정하고 서버에서도 한 번 더 정규화한다.

---

## 2차 호출 — 제품 추천 (텍스트, 직원 전용)

**입력**: 1차 결과의 `outfit` + 프리필터 통과 후보군 (카테고리별 6~8개)

```json
{
  "recommendations": [
    {
      "category": "bag",
      "items": [
        { "rank": 1, "product_id": "MCM-BAG-014", "reason": "올블랙 착장에 코냑 Visetos가 유일한 포인트로 들어옵니다." },
        { "rank": 2, "product_id": "MCM-BAG-003", "reason": "실루엣이 캐주얼한 핏을 정돈해 줍니다." },
        { "rank": 3, "product_id": "MCM-BAG-021", "reason": "야간 무드에 맞는 무광 블랙 마감." }
      ]
    },
    { "category": "wallet", "items": [ /* 동일 구조 3개 */ ] },
    { "category": "belt",   "items": [ /* 동일 구조 3개 */ ] }
  ],
  "styling_note": "코냑 포인트를 가방 하나로 몰아주고 나머지는 블랙으로 정리하는 조합을 권합니다."
}
```

---

## 3차 호출 — 선곡 (텍스트)

**입력**: 1차 결과의 `outfit`·`mood`·`concept_name` + Jamendo 검색 후보 10곡

후보는 `T1, T2 …` 라벨로 넘긴다. Jamendo 원본 id 는 숫자 나열이라 모델이 헷갈리고
`enum` 도 길어진다. 곡당 넘기는 정보는 제목·아티스트·길이·장르·악기·무드태그·
acoustic/electric·speed 뿐이다.

> Jamendo 응답의 `waveform` 필드는 숫자 700여 개다. **프롬프트에 절대 넣지 않는다** —
> 후보 10곡이면 이것만으로 수천 토큰이 소모된다.

```json
{
  "track_id": "T3",
  "reason": "강렬한 비트가 자신감 있는 무드에 잘 어울립니다."
}
```

- `track_id` 는 후보 라벨 `enum` 으로 제한하고, 서버가 후보 목록과 다시 대조한다.
- 실패 시 검색 1위를 쓰고 `reason` 은 `null` 로 둔다 — 화면에서 "AI 선곡" 배지가 붙지 않는다.
  실제로 AI 가 고르지 않았기 때문이다.
- **모델은 곡을 듣지 못하고 메타데이터만 본다.** 태그와 제목의 인상이 어긋나지 않도록
  프롬프트로 지시한다.

---

## 공통 원칙

- 응답은 JSON Schema 로 검증하고, 파싱 실패 시 1회 재시도 후 폴백 적용.
- **선택형 출력은 모두 `enum` + 서버 재검증의 2중 구조를 쓴다** — `product_id`, `track_id`,
  `mood_tags`, `genre_hint`. LLM 은 만들어내지 않고 고르기만 한다.
- 조명 값은 사전 정의 프리셋 범위 내로 클램핑하여 어색한 연출 방지.
- `temperature` 는 낮게 설정하여 같은 착장에 대한 결과 편차를 억제한다.
- `reason` 은 **40자 이내 한 줄**. 직원이 접객 중 흘깃 보고 쓸 수 있어야 한다.
- **`style_comment`(고객 노출용)에는 제품명·카테고리를 절대 포함하지 않는다.**
  프롬프트에 명시적으로 금지하고, 렌더링 전 서버에서도 한 번 더 검사한다.

> **프롬프트에 구체적인 예시를 주면 모델이 그대로 베낀다.**
> `query_tags` 예시로 `(좋음: boombap, bass, groove)` 를 적었더니 전혀 다른 두 착장이
> 동일한 태그 세 개를 내놓았다. 예시 대신 **분류 기준(무드→장르 매핑)** 을 주어
> 분화를 구조적으로 강제할 것.

---

## 세 출력의 일관된 구조

조명·제품·음향이 모두 같은 패턴을 따른다. 이것이 "AI 가 관여한다"를 설명 가능하게 만든다.

| 출력 | 후보 제공 | LLM 역할 | 서버 검증 |
|---|---|---|---|
| 조명 | — | 값 직접 생성 | 프리셋 범위 클램핑 |
| 제품 | 프리필터 21개 | 9개 선택 + 순위 + 이유 | ID 실존·카테고리 대조 |
| 음향 | Jamendo 10곡 | 1곡 선택 + 이유 | 라벨 `enum` + 후보 대조 |

> 초기 구현에서는 음향만 LLM 이 **검색어만 생성**하고 실제 곡은 Jamendo 검색 순서가 정했다.
> 조명·제품과 달리 결정권이 없어 일관성이 없었고, 결과적으로 무드와 무관한 곡
> (명상 음악 등)이 재생되는 문제가 있었다. 위 구조로 통일하여 해결.

---

## 요청 한도

**한 세션이 3회 호출한다.** 무료 등급 RPM 3 이면 한도에 즉시 닿는다.

문제는 429 가 났을 때 **폴백이 매끄럽게 받아버려 화면상으로는 정상으로 보인다**는 것이다.
심사위원이 연속으로 체험하면 AI 가 조용히 꺼진 상태로 시연될 수 있다.

대응:
- 발표 전 **결제 수단 등록 필수**
- 429 를 로그에 `[RATE LIMIT]` 으로 구분 표시
- 화면에 `AI 분석` / `폴백 프리셋` 배지로 실시간 노출
- 직원 카드의 추천은 세션당 1회만 계산하고 캐시 ([API_SPEC](API_SPEC.md))
