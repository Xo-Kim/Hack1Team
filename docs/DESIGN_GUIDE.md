# MCM Mood Mirror — 디자인 가이드

MCM(Modern Creation München, 1976)의 무드를 웹으로 옮기기 위한 토큰과 규칙.

**브랜드 성격 세 줄**
- 여행 가방 제조사의 정밀함 → 각진 모서리, 정확한 정렬, 각인된 라벨
- 코냑 비세토스 모노그램은 로고가 아니라 소재 → 배경으로만, 콘텐츠 위에 얹지 않음
- 럭셔리 × 스트리트 → 무겁지만 늙지 않은 모션, 과감한 스케일

**한 문장 원칙**
> 화면의 90%는 흑백. 코냑과 황동은 나머지 10%에만.

**대상 화면**

| | 고객 디스플레이 | 직원 패널 |
|---|---|---|
| 형태 | 가로형 웹 (거울 옆 배치) | 태블릿·데스크톱 |
| 톤 | 절제, 여백, 큰 비주얼 | 밀도, 대비, 즉시성 |

---

## 1. 컬러

### 토큰

```css
:root {
  /* Base */
  --mcm-white:        #FFFFFF;
  --mcm-onyx:         #111111;   /* 순흑 아님. 화면에서 눈이 덜 아프다 */

  /* Signature — 코냑 가죽 */
  --mcm-cognac:       #8A5A2B;
  --mcm-cognac-deep:  #4E3018;

  /* Metal trim — 가방 금속 부자재의 무광 황동 */
  --mcm-brass:        #A9834B;

  /* Utility */
  --mcm-ash:          #6B6B6B;   /* 보조 텍스트 */
  --mcm-line:         #E3E1DE;   /* 헤어라인. 회색 아닌 미세한 웜톤 */

  /* Mood — 서버 lighting 값 런타임 주입 */
  --mood-primary:     var(--mcm-cognac);
  --mood-accent:      var(--mcm-brass);
  --mood-brightness:  0.78;
}
```

### 대비값 (WCAG)

| 조합 | 비율 | 판정 |
|---|---|---|
| onyx on white | 18.9:1 | AAA |
| ash on white | 5.7:1 | AA (본문 가능) |
| cognac on white | 5.9:1 | AA (본문 가능) |
| white on cognac | 5.9:1 | AA |
| white on onyx | 18.9:1 | AAA |
| **brass on white** | **3.4:1** | **AA 미달 — 텍스트 금지** |
| brass on onyx | 5.5:1 | AA (큰 텍스트만 권장) |

### 사용 면적 규칙

색마다 허용 면적이 다르다. **이 비율이 무너지면 팔레트가 아무리 좋아도 촌스러워진다.**

| 색 | 허용 면적 | 구체적 용법 |
|---|---|---|
| white / onyx | 무제한 | 배경, 텍스트, 버튼 |
| ash | 텍스트만 | 보조 설명, 비활성 라벨 |
| line | 1px 선만 | 카드 테두리, 구분선, 언더라인 |
| **cognac** | **화면의 10% 이하, 한 화면에 1개소** | 버튼 호버 배경, 이미지 오버레이, 완료 버튼 |
| **brass** | **선과 점만. 면으로 절대 금지** | 1px 밑줄, 4px 스터드 도트, 포커스 링, 아이콘 스트로크 |

**황동이 가장 위험하다.** 금색 계열은 화면에서 면으로 쓰이는 순간 탁한 겨자색으로 앉는다. 실물 금속은 반사가 변해서 럭셔리하지만 화면은 그렇지 않다. 그라디언트·광택 효과는 어떤 경우에도 금지.

**코냑도 넓은 면으로 쓰면 칙칙해진다.** 정적인 큰 색면보다 **상태 변화(호버)나 반투명 오버레이**일 때 훨씬 세련되게 읽힌다.

### 금지

- 크림·베이지(`#F4F1EA` 계열) 배경 — MCM이 아니라 편집숍 톤
- 액센트 컬러 추가 (성공 초록·경고 빨강 등). 상태는 텍스트와 헤어라인으로 구분
- 직원 패널 우선순위(긴급/높음/보통/낮음)를 색으로 나누기

### 무드 필터

조명 대신 **카메라 프리뷰 위에 CSS 필터**로 무드를 표현한다.

**이 색은 브랜드 팔레트가 아니다.** AI가 착장을 분석해 내려준 `lighting` 값이 런타임에 주입되므로 세션마다 달라진다 — 차가운 착장이면 블루, 따뜻한 착장이면 앰버가 들어온다. 위의 면적 규칙(코냑 10% 이하 등)은 이 영역에 적용되지 않는다.

필터가 화면의 색을 담당하는 동안 **나머지 영역은 흑백으로 비워야** 균형이 맞는다. 필터 색과 브랜드 컬러가 한 화면에서 경쟁하면 둘 다 죽는다.

```css
.mood-overlay {
  background: var(--mood-primary);
  mix-blend-mode: soft-light;
  opacity: calc(var(--mood-brightness) * 0.6);
}

.mood-video {
  filter:
    saturate(1.1)
    contrast(1.05)
    brightness(var(--mood-brightness))
    sepia(0.12);
  transition: filter var(--dur-mood) var(--ease-mcm);
}
```

- 블렌드는 `soft-light` 또는 `overlay`만. `multiply`는 얼굴이 탁해지고 `screen`은 날아간다
- **오버레이 불투명도 상한 0.6.** 넘으면 필터가 아니라 색유리가 되고 고객이 자기 착장을 못 본다
- `sepia()`는 `0.15` 이하. 색온도를 흉내내려다 피부톤이 무너진다
- `lighting.effect === 'breathe'`일 때만 `--mood-brightness`를 ±0.05로 6s 이상 주기 진동. 그 외 정적
- 필터 영역 위 텍스트에는 `rgba(17,17,17,0.55)` 스크림 필수 — 동적 색이라 대비 보장 불가

---

## 2. 타이포그래피

### 서체 3종

```css
--font-display: 'Newsreader', serif;          /* 영문 헤드라인 */
--font-body:    'Pretendard', sans-serif;     /* 한글 + 영문 본문 */
--font-mono:    'JetBrains Mono', monospace;  /* 데이터 */
```

**세리프를 쓰는 이유** — MCM 로고타입은 1888년 독일 활자 Römische Antiqua 기반의 **저대비 세리프**다(Sharp Type, 2022). 헤드라인 세리프는 이 계열과 같아야 한다.

**허용** — 저대비·각진 19세기 인쇄체 계열: Newsreader, Source Serif 4, Instrument Serif
**금지** — 고대비 디돈 계열(Bodoni, Playfair)은 패션 잡지 톤, 인문주의 올드스타일(Garamond, Crimson)은 너무 부드럽다

### 스케일

```css
--type-hero:      clamp(2.5rem, 5vw, 4.5rem);   /* 영문 헤드라인 */
--type-h2:        clamp(1.5rem, 2.5vw, 2.25rem);
--type-body:      1rem;
--type-caption:   0.8125rem;
--type-label:     0.6875rem;
--type-mono-data: 0.875rem;
```

### 역할별 상세 스펙

| 역할 | 서체 | 크기 | 굵기 | 자간 | 행간 | 대소문자 |
|---|---|---|---|---|---|---|
| 영문 헤드라인 | display | hero | **500** | **-0.015em** | 1.1 | **문장형 (대문자 변환 금지)** |
| 한글 제목 | body | h2 | 500 | -0.01em | 1.4 | — |
| 본문 (한글) | body | body | 400 | 0 | **1.7** | — |
| 본문 (영문) | body | body | 400 | 0 | 1.6 | — |
| 보조 설명 | body | caption | 400 | 0 | 1.6 | — |
| 버튼 | body | body | 500 | **0.1em** | 1 | **UPPERCASE** |
| 라벨 | mono | label | 400 | **0.12em** | 1.2 | **UPPERCASE** |
| 데이터 | mono | mono-data | 400 | 0 | 1.4 | — |

### 자간 규칙 — 가장 자주 틀리는 부분

```css
/* 세리프 헤드라인 — 대문자로 바꾸지 않는다 */
.headline {
  font-family: var(--font-display);
  font-variation-settings: 'opsz' 72;
  font-weight: 500;
  letter-spacing: -0.015em;
  text-transform: none;
}

/* 대문자 라벨 — 반드시 벌린다 */
.label {
  font-family: var(--font-mono);
  font-size: var(--type-label);
  letter-spacing: 0.12em;
  text-transform: uppercase;
}
```

- **큰 글자는 조이고(-0.015em), 작은 대문자는 벌린다(+0.1~0.14em).** 이 두 가지가 타이포 규칙의 전부다
- **세리프 헤드라인을 대문자로 변환하지 말 것.** MCM 로고가 이미 대문자 세 글자라 둘이 싸운다. 세리프는 문장형에서 우아하다
- 헤드라인 굵기는 **500**. 700은 뉴스 헤드라인처럼 무겁고, 400은 큰 크기에서 힘이 빠진다
- Newsreader는 가변 폰트다. `opsz`를 크기에 맞춰 조정하면 스케일 전 구간이 깔끔하다 (헤드라인 72, 본문 16)

### 기타

- **Newsreader는 영문 전용.** 한글이 없어 Pretendard로 폴백되는데, 한 줄에 섞이면 어색하다. 영문 컨셉명("Golden Hour Drift")과 영문 헤드라인에만
- 한글은 무조건 **500 이하**. 700 볼드 한글은 무게가 아니라 소음
- 굵기는 400·500·700 세 단계만
- **모노스페이스는 진짜 데이터에만** — 세션ID(`#A-041`), 시각(`14:32`), 경과시간(`7분 경과`), 연도(`1976`). 장식으로 쓰면 각인의 의미가 사라진다

---

## 3. 형태와 여백

### 반경·테두리·그림자

```css
--radius-none:   0;      /* 버튼, 인풋 */
--radius-sm:     2px;    /* 카드, 배지, 체크박스 */
--border-hair:   1px;    /* 구조선 — 카드, 구분선, 배지 */
--border-action: 2px;    /* 조작 요소 — 버튼, 포커스 링 */
```

- **radius는 0 또는 2px.** 필(pill) 형태 전면 금지 — 배지도 사각으로
- **`box-shadow` 금지.** 깊이는 헤어라인과 여백으로만
- **선 두께는 두 가지뿐이다.** 구조선(카드 테두리·구분선·배지)은 1px, 조작 요소(버튼 보더·포커스 링)는 2px
- **버튼은 기본 상태부터 2px로 둔다.** 호버 때만 두꺼워지면 레이아웃이 밀려 버튼이 떨린다. 1px 황동은 흰 배경에서 대비 3.4:1이라 색이 거의 보이지 않는다
- 카드 테두리까지 2px로 올리지 말 것 — 화면이 무거워진다

### 여백

```css
--space-xs:  8px;
--space-sm:  12px;
--space-md:  24px;
--space-lg:  48px;
--space-xl:  96px;
```

**고객 화면은 여백이 럭셔리를 만들고, 직원 화면은 밀도가 업무 효율을 만든다.** 고객 화면은 스케일 상단(lg·xl)을, 직원 화면은 하단(xs·sm·md)을 주로 쓴다. 좁히고 싶은 충동이 들면 여백이 아니라 요소를 줄인다.

화면별 구체적 수치는 와이어프레임 확정 후 정한다.

### 이미지 비율

**4:5(제품)와 16:9(비주얼)** 두 가지로 고정. 3종 이상 섞이면 럭셔리 감각이 먼저 무너진다.

---

## 4. 모션

```css
--ease-mcm: cubic-bezier(0.16, 1, 0.3, 1);
--dur-fast: 220ms;   /* 버튼·호버 */
--dur-base: 480ms;   /* 화면 전환 */
--dur-slow: 720ms;   /* 비주얼 리빌 */
--dur-mood: 2400ms;  /* 무드 필터 전환 — 유일한 "쇼" */
```

- **바운스·스프링·오버슛 전면 금지**
- 이미지 호버는 `scale(1.03)` 480ms. 그 이상은 광고 배너
- 무드 필터 전환만 2.4초를 쓴다. 다른 곳에서 이만한 시간을 쓰지 않는다
- 로딩은 채도 있는 바가 아니라 **1px 헤어라인이 채워지는 형태**. 퍼센트 숫자 표시 안 함
- `prefers-reduced-motion: reduce`에서 모든 변환을 opacity로 대체

---

## 5. 모노그램

**허용** — 대기·로딩 화면의 전면 텍스처 / 한 섹션 전체를 덮는 배경(텍스트는 옆 칼럼에) / 푸터 상단 스트립 / 이미지 없는 카드 플레이스홀더

**금지** — 본문 텍스트 뒤 저투명도 / 버튼·아이콘·체크박스 / 무드 필터 화면과 동시 등장 / 한 화면 2회 초과 / 직원 패널 전체

투명도로 흐리게 만들어 "은은하게" 쓰려는 시도는 대부분 실패한다. **100% 선명하게, 면적만 제한.**

---

## 6. 시그니처 — 스터드 코너 마크

Stark 백팩 리벳에서 온 4px 황동 도트 하나. 애니메이션 없음, 설명 없음.

```css
.stud::before {
  content: "";
  display: block;
  width: 4px;
  height: 4px;
  background: var(--mcm-brass);
}
```

체크리스트 불릿도 이 도트로 대체(✓ 이모지 금지). 직원 패널에는 쓰지 않는다.

**이것 외의 장식은 전부 제거한다. 시그니처가 둘이면 시그니처는 없는 것이다.**

---

## 7. 컴포넌트

```css
/* Primary — 오닉스 배경, 호버 시 배경만 코냑 */
.btn-primary {
  background: var(--mcm-onyx);
  color: var(--mcm-white);
  border-radius: var(--radius-none);
  padding: 18px 40px;
  font-weight: 500;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  transition: background var(--dur-base) var(--ease-mcm);
}
.btn-primary:hover { background: var(--mcm-cognac); }

/* Secondary — 투명 배경, 호버 시 보더만 황동. 기본부터 2px */
.btn-secondary {
  background: transparent;
  border: 2px solid var(--mcm-onyx);
  color: var(--mcm-onyx);
  transition: border-color var(--dur-base) var(--ease-mcm);
}
.btn-secondary:hover { border-color: var(--mcm-brass); }

/* 포커스 — outline: none 절대 금지 */
:focus-visible {
  outline: 2px solid var(--mcm-brass);
  outline-offset: 2px;
}
```

| 컴포넌트 | 스펙 |
|---|---|
| 텍스트 링크 | 언더라인, ash → 호버 시 onyx |
| 카드 | **1px** line 보더, radius 2px, 흰 배경, **그림자 없음** |
| 체크박스 | 16×16px, radius 2px, 미체크 시 line 보더 / 체크 시 onyx 배경 + 흰 마크 |
| 배지 | radius 2px 사각, **1px** 보더, 배경 없음, 모노 라벨. **색으로 상태 구분 안 함** |
| 인풋 | 언더라인만(박스 금지). 기본 1px line, 포커스 시 **황동 2px**. textarea만 박스 허용 |
| 진행 표시 | 1px 헤어라인 트랙 + onyx 라인. 높이 1px 유지 |
| 빈 상태 | 상황 한 줄 + 다음 행동 링크 하나. 일러스트·아이콘 없음 |
| 푸터 | 오닉스 배경. 여기서만 모노그램 스트립. `MODERN CREATION MÜNCHEN · 1976` 모노 라벨 |

---

## 8. 카피 보이스

- 명사형 종결, 대문자 라벨. 영문 대문자를 쓸 거면 전 화면 일관되게
- **감탄사·이모지·느낌표 없음**
- "럭셔리한", "감각적인" 같은 형용사 금지 — 브랜드가 이미 말하고 있다
- 빈 상태에서 **사과하지 않는다**. `아직 담은 제품이 없습니다` + 링크 하나
- 에러는 무엇이 잘못됐고 무엇을 하면 되는지만
- 폴백 상황에서도 변명하지 않는다. `연출이 준비되었습니다` 정도의 담담한 톤

---

## 9. 안티패턴 체크리스트

**컬러**
- [ ] 황동을 면으로 썼다 (배경·아이콘 채우기)
- [ ] 황동을 텍스트에 썼다 (대비 3.4:1, AA 미달)
- [ ] 금색 그라디언트나 광택 효과가 있다
- [ ] 코냑 색면이 한 화면에 2개 이상이다
- [ ] 크림/베이지 배경을 썼다
- [ ] 상태 구분에 새 컬러를 추가했다

**타이포**
- [ ] 세리프 헤드라인을 대문자로 변환했다
- [ ] 세리프를 한글에 썼다
- [ ] 700 굵기 한글 헤드라인이 있다
- [ ] 대문자 라벨의 자간을 벌리지 않았다
- [ ] 모노스페이스를 장식으로 썼다 (데이터가 아닌 곳)
- [ ] 고대비 세리프(Bodoni·Playfair)를 썼다

**형태·모션**
- [ ] `box-shadow`가 있다
- [ ] 필(pill) 버튼이나 배지가 있다
- [ ] 버튼 보더가 1px이라 호버 색이 안 보인다
- [ ] 호버 시 보더가 두꺼워져 레이아웃이 밀린다
- [ ] 이미지 비율이 3종 이상 섞여 있다
- [ ] 바운스·스프링 모션이 있다
- [ ] 체크마크·경고 이모지를 아이콘으로 썼다

**화면별**
- [ ] (고객) 무드 오버레이가 0.6을 넘어 착장이 안 보인다
- [ ] (고객) 필터 영역 위 텍스트에 스크림이 없다
- [ ] (고객) 스터드 도트 외 장식이 있다
- [ ] (고객) 무드 필터 영역 옆에 브랜드 컬러 색면이 함께 있다
- [ ] (직원) 모노그램이나 장식이 있다
- [ ] (직원) 세션ID·시각이 모노스페이스가 아니다
- [ ] (직원) 고객 화면처럼 여백을 크게 잡아 밀도가 낮다

---

## 10. 품질 기준

- 고객 디스플레이는 실사용 해상도 고정, 세이프존(상하 5%) 확보
- 포커스 링은 황동 2px, **`outline: none` 금지**
- 이미지·비디오는 `width`/`height` 또는 `aspect-ratio` 명시로 CLS 0 수렴
- 카메라 프리뷰와 무드 필터는 GPU 합성 속성(`opacity`, `filter`, `transform`)만 사용
- 히어로 비주얼 LCP 2.5초 이내
