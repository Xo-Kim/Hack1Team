# MCM Mood Mirror — 코어 파이프라인

**촬영(3초 카운트다운) → AI 분석 → 조명 연출 + 음향 재생 + 제품 추천** 파이프라인의 구현체.

| 문서 | 내용 |
|---|---|
| [PRD](docs/PRD_MCM_Mood_Mirror.md) | 문제 정의 · 브랜드 전략 · 시장 논거 · KPI |
| [docs/API_SPEC.md](docs/API_SPEC.md) | **엔드포인트 계약 · 세션 상태 · 오류 코드** |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 시스템 구성 · 카탈로그 · 추천 로직 · 폴백 |
| [docs/LLM_SPEC.md](docs/LLM_SPEC.md) | LLM 3회 호출 스펙 |
| [docs/EXPERIENCE_SPEC.md](docs/EXPERIENCE_SPEC.md) | 조명 · 음향 · 공유 설계 |
| [docs/PRIVACY.md](docs/PRIVACY.md) | 이미지 처리 · 저장 금지 제약 |
| [docs/DESIGN_GUIDE.md](docs/DESIGN_GUIDE.md) | **컬러 · 타이포 · 형태 · 모션 토큰과 규칙** |

---

## 실행

터미널 2개가 필요하다.

**1) 백엔드** (`http://localhost:8080`)

```bash
cd Back && ./gradlew bootRun
```

**2) 프론트엔드** (`http://localhost:5173`)

```bash
cd Front && npm install && npm run dev
```

브라우저에서 `http://localhost:5173` 을 연다. Vite가 `/api` 를 8080으로 프록시하므로 CORS 설정 없이 동작한다.

### API 문서 (Swagger)

백엔드를 띄운 뒤 아래 주소로 들어가면 API 를 브라우저에서 바로 호출해 볼 수 있다.

| | 주소 |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |

프론트 없이 백엔드만 확인할 때 유용하다. 문서는 `1. 고객 미러` / `2. 직원 응대` 로
묶여 있어 자기 파트만 보면 된다. `analyze` 는 `image` 에
`data:image/jpeg;base64,...` 형태의 data URL 을 넣어야 한다.

> **springdoc 은 3.x 를 써야 한다.** 2.x 는 Spring Boot 3 / Jackson 2 전용이라
> 이 프로젝트(Boot 4 / Jackson 3)에서는 동작하지 않는다.
>
> `ResponseEntity<?>` 처럼 와일드카드를 반환하는 핸들러는 springdoc 이 타입을 추론하지 못해
> 응답 스키마가 문서에서 통째로 빠진다. 지금은 컨트롤러가 구체 타입을 반환하고
> 예외 변환을 `ApiExceptionHandler` 가 맡으므로 이 문제가 없다.

> **카메라는 `localhost` 또는 https 에서만 열린다.** LAN IP(`192.168.x.x`)로 접속하면
> 브라우저가 `getUserMedia` 를 막는다. 다른 기기에서 테스트하려면 https 터널이 필요하다.

### 포트 바꾸기

포트가 겹치면 환경변수로 덮어쓴다. 기본값은 8080 / 5173.

```bash
SERVER_PORT=8081 ./gradlew bootRun
```

```bash
BACKEND_URL=http://localhost:8081 FRONT_PORT=5174 npm run dev
```

프론트는 `strictPort` 라 포트가 이미 쓰이면 조용히 다른 번호로 옮겨가지 않고 실패한다.
안 그러면 프록시 대상과 실제 접속 포트가 어긋나 원인을 찾기 어려워진다.

### API 키 넣기

키가 없으면 **자동으로 mock 모드**로 동작한다. 폴백 프리셋 5종과 프리필터만으로
전체 플로우가 끝까지 돌아가므로, 키 없이도 데모가 가능하다.

키를 넣는 방법은 두 가지이고, **둘 다 동시에 지원된다.**

**방법 1 — 로컬 yaml (권장)**

```bash
cp Back/application-local.yaml.example Back/application-local.yaml
# 파일을 열어 실제 키를 채운다
```

`Back/application-local.yaml` 은 `.gitignore` 에 등록되어 있다.
CLI·IntelliJ·jar 어디서 띄우든 동일하게 동작하므로 이쪽이 가장 덜 헷갈린다.

**방법 2 — 환경변수 / IntelliJ 실행 구성**

```bash
OPENAI_API_KEY=sk-... JAMENDO_CLIENT_ID=... ./gradlew bootRun
```

**우선순위: 환경변수 > application-local.yaml > (없음 → mock)**
검증 결과, 환경변수를 빈 값으로 두면 yaml 에 값이 있어도 mock 으로 떨어진다.
IntelliJ 실행 구성에 빈 값이 남아 있지 않은지 확인할 것.

> **`application.yaml` 에 키를 직접 쓰지 말 것.** 이 파일은 git 에 커밋된다.

### 키가 들어갔는지 확인

```bash
curl http://localhost:8080/api/health
```

`llmMode: live` / `musicMode: jamendo` 로 바뀌면 키가 들어온 것이다.

**단, `live` 는 "키가 들어왔다"는 뜻이지 "키가 유효하다"는 뜻이 아니다.**
잘못된 키도 `live` 로 표시되고 호출 시점에 401 이 난다. 실제 성공 여부는 로그로 본다.

| 로그 | 의미 |
|---|---|
| `analyze done ... fallback=false` | LLM 호출 성공 (사용량에도 잡힌다) |
| `1차 Vision 분석 실패 → 401 Unauthorized` | 키가 잘못됨 |
| `jamendo hit: tags=... → '곡명' / 아티스트` | 음원 매칭 성공 |
| `jamendo 응답 실패: Invalid Client Id` | client_id 가 잘못됨 |

모델·엔드포인트는 `Back/src/main/resources/application.yaml` 에서 바꾼다.
OpenAI 호환 엔드포인트라면 `LLM_BASE_URL` 만 바꿔도 되고,
다른 제공사로 옮기려면 `LlmClient` 한 클래스만 교체하면 된다.

### Jamendo client_id 발급

1. [devportal.jamendo.com](https://devportal.jamendo.com) → **Sign up** (무료)
2. **Jamendo Apps** 메뉴에서 애플리케이션 생성
3. 플랜은 **"Read only"** 로 충분하다 (검색·재생만 하므로)
4. 발급된 Client ID 를 위 두 방법 중 하나로 넣는다

키가 없으면 음원 검색을 건너뛰고 절차적 앰비언스로 재생한다.

---

## 흐름

API 는 **고객용과 직원용 두 경로로 갈라져 있다.** 고객이 도달 가능한 경로에는
제품 추천을 돌려주는 엔드포인트가 존재하지 않는다. 이게 이 서비스의 설계 원칙이
"프론트가 안 부르기로 한 약속"이 아니라 구조적 사실이 되는 지점이다.

```
고객 미러 (/api/mirror/**)                    직원 단말 (/api/staff/**)
──────────────────────────                    ──────────────────────────
                                              GET  /notifications  (SSE)
POST   /sessions                                ↑ 상시 구독. 폴백은 목록 폴링
  ↓ mirrorId·storeId 필수                       │
POST   /sessions/{id}/consent                   │
  ↓ 이후 3·2·1 은 클라이언트에서만              │
POST   /sessions/{id}/analyze  ─── 3~7초 ───▶  │  (앞단 전이는 알림 없음)
  ↓ 이미지는 RAM 에서만 처리되고 폐기          │
[연출] 조명 + 음향 동시 페이드인               │
  ↓                                            │
POST   /sessions/{id}/assist-request  ══push══▶│ event: assist_requested
       /sessions/{id}/self-browse     ══push══▶│ event: self_browsing
                                               ↓  60초 미확인 → 재알림
                                        GET  /sessions/{id}
                                          무드·팔레트·컨셉명 + 카테고리별 3종
                                          (세션당 1회만 계산 후 캐시)
                                               ↓
GET    /sessions/{id}  ◀──────────────  POST /sessions/{id}/accept
  musicDucked: true                       다른 직원이 점유 중이면 409
  볼륨만 낮추고 조명은 유지               POST /sessions/{id}/release
                                          POST /sessions/{id}/complete → ENDED
```

**알림은 SSE 푸시다.** 서버 → 직원 단방향이라 WebSocket 을 쓰지 않았다. 일반 HTTP 라
매장 방화벽을 그대로 통과하고 끊기면 브라우저가 알아서 재연결한다. 실측 전달 0.4초.
스트림이 막혀도 `GET /api/staff/sessions` 폴링이 폴백으로 남아 있다.

### 두 경로를 갈라 놓은 장치

| 장치 | 하는 일 |
|---|---|
| 컨트롤러 분리 | `CustomerMirrorController` 에는 추천을 돌려주는 메서드가 없다 |
| 서비스 분리 | `CustomerSessionService` 는 `MirrorService.recommend()` 를 호출하지 않는다 |
| DTO 파일 분리 | 추천이 실리는 레코드는 `StaffPayloads` 에만 있다 |
| CORS | 고객 프론트 출처에는 `/api/mirror/**` 만 열려 있다 (`WebConfig`) |

### 화면 구성

| 화면 | 내용 | 상태 |
|---|---|---|
| 미러 디스플레이 `/` | 조명·음악·컨셉명 — **감각** | **완료.** 세션 API 대응 |
| 직원 태블릿 `/staff` | 무드·팔레트·추천 3종·근거 — **지능** | **완료.** SSE 알림 + 중복 응대 방지 |

두 화면은 라우트로 갈라져 있고 서로의 데이터에 닿지 않는다. 고객 화면 코드에는
추천을 부르는 경로 자체가 없다 — `api.ts` 가 `mirror` / `staff` 로 나뉘어 있고,
미러 컴포넌트는 `staff.*` 를 import 하지 않는다.

---

## 구조

```
Back/
  config/MoodMirrorProperties     설정 바인딩 (LLM · 음원 · CORS · 세션 TTL)
  config/OpenApiConfig            Swagger 문서 메타 (제목 · 설명 · 서버)
  controller/CustomerMirrorController  /api/mirror/**  — 고객 파트 담당
  controller/StaffController          /api/staff/**   — 직원 파트 담당
  controller/HealthController         /api/health     — 공통
  controller/ApiExceptionHandler      예외 → HTTP 상태 변환 (양쪽 공유)
  service/CustomerSessionService  세션 전이 오케스트레이션. 추천에 닿지 않는다.
  service/StaffService            대기 목록 · 응대 카드 · 중복 응대 판정 · 추천 캐시
  service/StaffNotifier           SSE 알림 발신 · 60초 재알림 · 하트비트
  service/SessionReaper           만료 세션 주기 정리 (@Scheduled)
  service/LlmClient               OpenAI 호환 호출 + JSON Schema 강제 + 값 클램핑
  service/JamendoClient           무드 → CC 음원 검색 (⚠ 실제 키로 미검증)
  service/CatalogService          카탈로그 로딩 + 룰 프리필터 스코어링
  service/MirrorService           파이프라인 조립 + 제품 ID 재검증
  service/FallbackPresets         프리셋 5종 (이미지 해시로 결정 → 같은 사진은 같은 결과)
  service/SessionStore            인메모리 세션 + 전이 이벤트 발행. 이미지는 담지 않는다.
  resources/catalog.json          MCM 실제 제품 44종 (가방15/지갑15/벨트14)

Front/
  hooks/useCamera                 getUserMedia + 프레임 캡처
  hooks/useIdleTimeout            무입력 3분 → 대기 화면 복귀
  lighting.ts                     Lighting 스펙 → CSS 변수
  audio/moodAudio.ts              재생 단일 창구 (음원 ↔ 앰비언스 폴백)
  audio/moodSynth.ts              MusicSpec → Web Audio 절차적 앰비언스
  customer/MirrorApp.tsx          고객 상태 머신 (대기→동의→촬영→분석→연출→선택)
  customer/screens.tsx            와이어프레임 n3·n4·n6·n9·n12·n14
  staff/StaffApp.tsx              직원 단말. 이 앱만 추천을 본다
  staff/screens.tsx               와이어프레임 n28·n29·n33·n35
  hooks/useStaffNotifications     SSE 구독 + 폴링 폴백
  styles.css                      조명 레이어 · blend · 그레인 · breathe/sweep
```

### 조명이 "색 변화"가 아니라 "조명"으로 보이는 이유

`styles.css` 의 3단 구성이다. ([EXPERIENCE_SPEC §1.1](docs/EXPERIENCE_SPEC.md))

1. **`.mirror` 에 CSS filter** — 카메라 영상 자체의 밝기·대비·채도를 바꾼다.
   화면 속 내 얼굴과 옷의 색이 변하므로 조명 인지가 배경이 아니라 자기 자신에서 일어난다.
2. **`.light--primary` (soft-light) + `.light--accent` (overlay)** — 색을 인물 위에 얹는다.
3. **2.4~2.6초 전환** — 즉시 바뀌면 "색 바뀜", 서서히 바뀌면 "조명이 들어옴"으로 읽힌다.

밝은 씬에서는 틴트 투명도를 자동으로 낮춘다. 안 그러면 화면이 하얗게 날아간다.

### 음향

`MoodAudio` 가 단일 창구다. 실제 음원이 있으면 재생하고, 없거나 실패하면
절차적 앰비언스로 폴백한다. **소리가 아예 안 나는 상태는 만들지 않는다.**

```
분석 결과의 MusicSpec
   ├─ energy       → Jamendo speed (verylow ~ veryhigh)
   └─ queryTags + genreHint → fuzzytags
              ↓
      Jamendo 후보 10곡 (제목·아티스트·길이·장르·악기·무드태그·라이선스)
              ↓
      GPT 가 그중 1곡 선택 + 이유  ← 제품 추천과 같은 패턴
              ↓
      서버가 후보 목록과 대조 → <audio> 재생 (loop)
      실패하면 검색 1위, 후보가 없으면 MoodSynth 앰비언스
```

**세 출력이 모두 같은 구조다.**

| | 후보 제공 | GPT 역할 | 서버 검증 |
|---|---|---|---|
| 조명 | — | 값 직접 생성 | 범위 클램핑 |
| 제품 | 프리필터 21개 | 9개 선택 + 순위 + 이유 | ID 실존·카테고리 대조 |
| 음향 | Jamendo 10곡 | 1곡 선택 + 이유 | 라벨 enum + 후보 대조 |

후보는 `T1, T2...` 라벨로 넘긴다. Jamendo 원본 id 는 숫자 나열이라 모델이 헷갈린다.
**`waveform` 필드(숫자 700여 개)는 절대 프롬프트에 넣지 말 것** — 후보 10곡이면 이것만으로 수천 토큰이다.

`track.reason` 이 있으면 GPT 가 고른 곡, `null` 이면 검색 1위 폴백이다. 화면 배지가 이를 구분해 보여준다.

`JamendoClient` 는 태그를 좁게 걸면 0건이 되기 쉬워 **3단계로 넓혀가며 재시도**한다
(전체 태그 → 장르만 → `ambient`). LLM 이 뱉는 `"dark r&b"` 같은 구는 URL 을 깨뜨리므로
알파벳/숫자만 남겨 단어로 쪼개고 2글자 이하는 버린다.

**재생 중인 곡의 아티스트명과 출처 링크는 화면에서 지우지 말 것.**
Jamendo 의 CC 라이선스는 대부분 저작자 표시(BY)를 요구한다. 장식이 아니라 준수 요건이다.

검색은 `order` 가 아니라 `boost` 를 쓴다. 공식 문서 경고대로 `fuzzytags` 검색에
`order` 를 지정하면 **검색 관련성 순서가 통째로 사라지고 인기순으로만 정렬되어**
무드 매칭이 무의미해진다.

> **⚠ 실제 매장 도입 시 라이선스 확인 필요**
> Jamendo 응답의 `licenses.ccnc` 를 읽어 `MusicTrack.commercialOk` 로 내려보낸다.
> 별도 API 파라미터 없이 응답만으로 판별 가능하다.
>
> **문제는 실측 결과 후보 10곡이 전부 비상업(NC) 조건이었다는 것이다** (triphop 검색 기준).
> 해커톤 시연은 무관하지만 매장 영업은 상업적 이용이므로 그대로는 못 쓴다.
> 실서비스 전에 `probackground=true`(매장 배경음악 상업 프로그램 등록 곡만) 로
> 좁히거나, 브랜드 큐레이션 플레이리스트로 전환해야 한다.
> 지금은 후보를 거르지 않는다 — 거르면 후보가 0곡이 되기 때문이다.

절차적 앰비언스(`MoodSynth`)는 `key`/`scale`/`bpm`/`energy` 를 실제로 소비한다 —
근음+5도 드론, 느린 필터 LFO, 펜타토닉 아르페지오, 생성된 IR 리버브,
그리고 **킥·스네어·하이햇·서브베이스 비트**.

비트가 있는 이유: MCM 의 음악적 뿌리는 힙합·스트릿이다(PRD §3.2). 드론만 깔면
스파 음악처럼 들려 브랜드와 정반대가 된다. 리듬이 방향을 잡아준다.

드럼은 `setInterval` 로 직접 치지 않는다. 타이밍이 흔들려 바로 티가 나기 때문에,
짧은 주기로 깨어나 `ctx.currentTime` 기준으로 앞당겨 예약하는 **lookahead 스케줄링**을 쓴다.

### 장르 큐레이션

`genre_hint` 는 스키마 `enum` 으로 강제한다 (`product_id` · `mood_tags` 와 같은 방식).
기본값은 hiphop / rnb / soul / funk / electronic 계열이고, ambient·lounge 는
아주 미니멀한 착장에만 쓰도록 프롬프트에 명시했다.

> 프롬프트에 `(좋음: boombap, bass, groove)` 같은 **구체적 예시를 주면 모델이 그대로 베낀다.**
> 실제로 전혀 다른 두 착장이 동일한 태그 세 개를 내놓은 적이 있다.
> 예시 대신 무드→장르 매핑을 주어 분화를 강제할 것.

### 소리가 멈추는 조건

무한 재생을 막는 장치가 셋 있다.

| 조건 | 동작 |
|---|---|
| **무입력 3분** | 연출·음향 종료 후 대기 화면 복귀 (`IDLE_TIMEOUT_MS`) |
| **음소거 버튼** | 조명은 유지하고 소리만 끈다 |
| **다시 하기 / 언마운트** | 페이드아웃 후 AudioContext 종료 |

무입력 타이머는 `pointerdown / pointermove / keydown / touchstart / wheel` 로 리셋된다.
응대 중에 갑자기 리셋되지 않도록 하기 위한 것이다.

---

## 할루시네이션 차단 ([ARCHITECTURE §3.2](docs/ARCHITECTURE.md))

존재하지 않는 제품이 직원에게 전달되면 서비스 신뢰가 무너진다. 3중으로 막는다.

1. LLM은 **`product_id` 만 선택**한다. 제품명·가격·위치는 전부 DB에서 렌더링된다.
2. JSON Schema의 **`enum` 에 후보 ID를 열거**해 디코딩 단계에서 강제한다.
3. 서버가 반환 ID를 **카탈로그와 재대조**한다 — 미존재/카테고리 불일치/중복은 폐기하고
   프리필터 상위로 자리를 메운다.

`styleComment`(고객 노출용 유일한 LLM 텍스트)에 제품명이 섞이면 서버가 문장을 통째로 버린다.

---

## 프라이버시 구현 (PRD §16)

- 이미지는 **multipart가 아니라 JSON base64**로 받는다. multipart는 임계값을 넘으면
  서블릿이 디스크에 임시 파일을 만들기 때문에, 디스크 경로 자체를 만들지 않는 쪽을 택했다.
- 세션에는 **분석 결과 텍스트만** 들어간다. 이미지 바이트는 참조가 남지 않는다.
- base64 디코딩 실패 시 예외 메시지를 새로 만든다 — 기본 메시지에 원문이 실리기 때문.
- 세션은 TTL(기본 15분) 경과 시 조회 시점에 즉시 폐기된다.

> 현재는 외부 API를 호출한다. 실서비스의 온프렘·폐쇄망 전환은 [PRIVACY §2](docs/PRIVACY.md) 로드맵 참고.

---

## 아직 없는 것 (PRD 대비)

| PRD 기능 | 상태 |
|---|---|
| F-7 분기 선택 (직원 도움 / 혼자 볼게요) | 컨셉 카드의 버튼으로 축약. 정식 분기 화면 없음 |
| F-9 직원 실시간 알림 | **완료.** SSE 푸시 + 60초 재알림. 폴링은 폴백으로 유지 |
| F-10 직원 화면 (별도 라우트) | **완료.** `/staff` |
| F-11 응대 모드 | **완료.** `musicDucked` 로 볼륨만 하향, 조명 유지 |
| F-14 리셋 (타임아웃) | **완료.** 무입력 3분 → 서버 `reset` 호출 |
| F-18 진행 단계 리빌 | 있음 |
| 실제 조명·음향 하드웨어 | 범위 외. 스펙만 하드웨어 중립으로 정의 |

## 실제 키로 검증된 것 (2026-08-04)

| 항목 | 결과 |
|---|---|
| OpenAI 1차 Vision | ✅ `fallback=false`, 4.1~5.4초 (PRD 목표 p90 8초 이내) |
| 착장 판독 | ✅ 올블랙+코냑 → formality 0.7 / 파스텔 → 0.3 |
| **`product_id` enum 제약** | ✅ **9/9 실존 ID, 카테고리 일치, 폐기 0건** |
| `styleComment` 제품 언급 | ✅ 없음 |
| 무드별 판별력 | ✅ 상반된 착장 2건에서 추천 겹침 **0/9** |
| Jamendo 음원 매칭 | ✅ 무드별로 다른 곡, 1차 시도에서 적중 |
| energy → speed 매핑 | ✅ 0.4→medium, 0.3→low |

### ⚠ OpenAI 요청 한도

무료 등급은 **gpt-4o 분당 3회(RPM 3)** 다. 한 세션이 **3회**(Vision → 추천 랭킹 → 선곡)를
쓰므로 **한도에 바로 닿는다.** 429 가 나면 폴백 프리셋이 매끄럽게 받아버려서
화면만 보면 정상 동작처럼 보인다 — 데모 중 이걸 모르고 넘어가는 것이 가장 위험하다.

- 로그에 `[RATE LIMIT]` 으로 구분해 찍는다.
- 화면 배지가 `AI 분석` / `폴백 프리셋` 을 구분해 보여준다.
- **발표 전에 결제 수단을 등록해 한도를 올릴 것.**

### 응답 시간 (실측)

| 구간 | 시간 |
|---|---|
| 1차 Vision (gpt-4o) | ~5초 |
| Jamendo 검색 | ~0.5~1초 |
| 선곡 (gpt-4o-mini) | ~1초 |
| **합계** | **5.3~7.1초** |

PRD 목표는 p90 8초. 안에 들지만 여유가 크지 않다. 더 줄여야 하면
선곡을 별도 엔드포인트로 빼서 조명 먼저 켜고 음악을 뒤이어 붙이는 방법이 있다
(조명 전환이 2.5초라 그 안에 들어오면 동시에 시작한 것처럼 느껴진다).

### 아직 검증 못 한 것

- 실제 사람 사진이 아니라 **합성 이미지**로 검증했다. 실제 착장 사진에서의 판독 품질은 리허설에서 확인 필요.
- 같은 착장 반복 시 결과 편차(PRD §19 리스크)는 측정하지 않았다.
- 브라우저에서 Jamendo 음원이 실제로 **소리가 나는지**는 미확인 (URL 응답까지만 확인).
- **GPT 는 곡을 듣지 못하고 메타데이터만 본다.** 실제로 제목이 "Angry" 인 곡을
  "차분한 무드에 어울린다"며 고른 사례가 있었다. 제목의 인상도 함께 보도록
  프롬프트를 보강했으나 그 효과는 아직 재검증하지 않았다.

---

## 제품 카탈로그

`Back/src/main/resources/catalog.json` — **44종** (가방 15 / 지갑 15 / 벨트 14).

`kr.mcmworldwide.com` 공식 온라인 스토어에서 2026-08-04 기준으로 수집했다.

| 필드 | 출처 |
|---|---|
| `id` (스타일 코드), `name`, `priceKrw`, `imageUrl`, `productUrl` | **공식 사이트 실제 데이터** |
| `line`, `material`, `size`, `colors` | 공식 제품명·이미지 슬러그에서 파생 |
| `styleTags`, `formality` | **수기 부여** — 추천 로직용 |
| `storeLocation` | **수기 부여, 매장마다 다름** — 실제 매장 레이아웃에 맞춰 교체 필요 |

이미지는 MCM 이미지 CDN을 그대로 참조한다.

```
https://images.mcmworldwide.com/i/mcmworldwide/{스타일코드}_01/{슬러그}?w=400
```

`w` 파라미터로 크기를 조절한다 (`wid` 는 무시되고 원본 2000px 이 온다).
존재하지 않는 경로도 **200 + 4.4KB 플레이스홀더 이미지**를 돌려주므로,
URL 검증 시 상태 코드나 용량만 보면 안 되고 플레이스홀더와 해시를 대조해야 한다.

### 카탈로그를 늘리거나 교체할 때

`styleTags` 는 `LlmClient` 의 `MOOD_SCHEMA` 안 `mood_tags` enum과 **같은 어휘**를 써야 한다.
어휘가 어긋나면 프리필터의 태그 교집합 점수가 항상 0이 되어 추천 품질이 무너진다.
두 곳을 함께 고칠 것.

새로운 컬러를 추가하면 두 군데를 같이 손봐야 한다.

- `CatalogService.COLOR_FAMILY` / `ACCENT_FAMILIES` — 색상 조화도 계산
- `Front/src/lighting.ts` 의 `SWATCH` — 스와치 표시색

`COLOR_FAMILY` 는 `Map.ofEntries` 라 **키가 중복되면 기동 시점에 죽는다.**
컴파일은 통과하므로 반드시 한 번 띄워볼 것.
