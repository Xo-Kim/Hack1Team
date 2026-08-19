# API 명세

MCM Mood Mirror 백엔드 API. 구현된 코드 기준이며, 실행 중인 서버의
`/swagger-ui.html` 에서 직접 호출해 볼 수 있다.

---

## 경로가 둘로 갈라져 있다

| 경로 | 사용 화면 | 담당 | 추천 노출 |
|---|---|---|---|
| `/api/mirror/**` | 미러 디스플레이 | 고객 파트 | **없음** |
| `/api/staff/**` | 직원 태블릿·모바일 | 직원 파트 | 있음 |
| `/api/health` | 공통 | — | — |

**고객 경로에는 제품 추천을 반환하는 엔드포인트가 존재하지 않는다.** 고객 화면에 AI
추천을 노출하지 않는 것이 이 서비스의 핵심 원칙인데, 고객이 도달 가능한 경로에 추천
엔드포인트가 하나라도 남아 있으면 그 원칙은 "프론트가 안 부르기로 한 약속"으로 떨어진다.

원칙을 구조로 만들기 위해 네 겹으로 갈라 두었다.

| 겹 | 장치 |
|---|---|
| 컨트롤러 | `CustomerMirrorController` 에 추천 반환 메서드가 없다 |
| 서비스 | `CustomerSessionService` 가 `MirrorService.recommend()` 를 호출하지 않는다 |
| DTO | 추천이 실리는 레코드는 `StaffPayloads` 에만 있다 |
| CORS | 고객 프론트 출처에는 `/api/mirror/**` 만 열려 있다 |

---

## 세션 상태

모든 세션은 아래 상태 중 하나에 있다. 허용되지 않은 전이는 **409 `illegal_state`** 다.

```
IDLE ──▶ CONSENTED ──▶ ANALYZING ──▶ MOOD_ACTIVE
                                          │
                                          ├─▶ ASSIST_REQUESTED ⇄ ASSIST_ACCEPTED
                                          │
                                          └─▶ SELF_BROWSING ──▶ ASSIST_REQUESTED

(연출 이후 모든 상태) ──▶ ENDED       // 고객이 직접 마침
(종료를 제외한 모든 상태) ──▶ EXPIRED  // 타임아웃 · 리셋
```

| 상태 | 의미 | 직원 목록 노출 |
|---|---|---|
| `IDLE` | 세션은 열렸으나 아직 동의 전 | — |
| `CONSENTED` | 촬영·분석 동의 완료 | — |
| `ANALYZING` | 분석 진행 중 | — |
| `MOOD_ACTIVE` | 조명·음악·컨셉명 적용됨 | — |
| `ASSIST_REQUESTED` | 고객이 직원 도움을 요청 | **표시** (`needsAssist: true`) |
| `ASSIST_ACCEPTED` | 특정 직원이 응대 시작 | **표시** (`needsAssist: false`) |
| `SELF_BROWSING` | 고객이 혼자 보기를 선택 | **표시** (`needsAssist: false`) |
| `ENDED` | **정상 종료** — 고객이 직접 마침 | — (목록에서 사라짐) |
| `EXPIRED` | 무입력 타임아웃 또는 리셋 | — (목록에서 사라짐) |

> **`ENDED` 와 `EXPIRED` 를 나눈 것은 지표 때문이다.** 핵심 지표가 완주 세션 수인데
> 종료 경로가 하나뿐이면 "경험을 마친 고객"과 "그냥 떠난 고객"이 같은 값으로 집계된다.
> **세션을 끝낼 수 있는 것은 고객뿐이다.** 직원의 `complete` 는 응대만 닫고
> 세션은 `MOOD_ACTIVE` 로 되돌린다 — 고객이 아직 거울 앞에 있는데 화면이 꺼지면
> 그 자체로 쫓아내는 신호가 된다.

**3·2·1 카운트다운은 서버 상태가 아니다.** 서버는 카운트다운을 관측할 수단이 없고
이미지가 도착해야 비로소 알 수 있으므로 클라이언트 UI 단계로 남긴다.

---

## 고객 미러 — `/api/mirror`

### `POST /api/mirror/sessions`

미러 대기 화면에서 고객이 반응하면 세션을 연다. 분석보다 먼저 세션을 여는 이유는
직원 알림에 어느 미러인지가 필요하고, 동의를 거부했거나 중간에 이탈한 고객도 지표에
남겨야 하기 때문이다.

```json
{ "mirrorId": "mirror-01", "storeId": "mcm-seoul", "mirrorLabel": "2F 피팅룸 A" }
```

```json
{ "sessionId": "01KZX8...", "state": "IDLE" }
```

`mirrorId` 와 `storeId` 는 필수. 어느 미러인지 모르는 세션은 직원 화면에 띄울 수
없으므로 **400** 으로 거절한다.

### `GET /api/mirror/sessions/{sessionId}`

미러 화면이 폴링하는 경로.

```json
{
  "sessionId": "01KZX8...",
  "state": "ASSIST_ACCEPTED",
  "elapsedSeconds": 84,
  "analysis": { "outfit": {...}, "mood": "...", "conceptName": "...", "lighting": {...}, "music": {...} },
  "track": { "id": "...", "title": "...", "artist": "...", "audioUrl": "..." },
  "fallback": false,
  "musicDucked": true
}
```

- `musicDucked` 가 true 면 직원 응대가 시작된 것이다. **음악 볼륨을 낮추고 조명은 유지**한다.
  클라이언트가 상태값으로 추론하지 않도록 서버가 직접 알려준다.
- **직원 이름은 내려가지 않는다.** 고객 화면 안내는 `state` 로 만든다 —
  `ASSIST_REQUESTED` 는 "곧 도와드리겠습니다", `ASSIST_ACCEPTED` 는 "잠시만 기다려 주세요".
- 종료된 세션에서는 `analysis` · `track` 이 null 이다. 만료 직후 도착한 폴링 응답이
  조명·음악을 되살리는 것을 막는다.
- **이 응답에 추천 필드를 추가하지 말 것.**

### `POST /api/mirror/sessions/{sessionId}/consent`

촬영·분석 동의. `IDLE → CONSENTED`.

### `POST /api/mirror/sessions/{sessionId}/analyze`

```json
{ "image": "data:image/jpeg;base64,..." }
```

`sessionId` 는 경로 변수로 받으므로 본문에 없다. 양쪽에 두면 서로 다른 값이 들어왔을 때
무엇을 믿을지가 애매해진다.

multipart 대신 JSON 을 쓰는 이유는 프라이버시다. multipart 는 임계값을 넘으면 서블릿
컨테이너가 디스크에 임시 파일을 만든다. "이미지를 저장하지 않는다"를 지키려면 디스크
경로 자체를 만들지 않는 편이 확실하다.

내부적으로 LLM 을 2회 호출한다 (Vision 분석 → 후보 곡 중 선곡). **응답 시간 실측 3~7초.**

응답은 `AnalyzeResponse` — `sessionId` · `analysis` · `track` · `fallback` · `note`.

- **LLM 이 실패해도 200 을 돌려준다.** 이때 `fallback: true` 이고 사전 정의 프리셋 5종 중
  하나가 적용된다 (이미지 해시 기준이라 같은 사진은 같은 결과). 고객 경험은 성공과
  동일해야 하므로 화면에는 차이를 드러내지 않는다.
- `track` 이 null 이면 프론트가 절차적 앰비언스로 폴백한다.

> **이미지 검증이 상태 전이보다 먼저 일어난다.** 순서가 반대면 깨진 이미지를 받은 세션이
> `ANALYZING` 에 갇힌다 — 거기서 갈 수 있는 곳은 `MOOD_ACTIVE` 뿐이라 재촬영이 영원히
> 409 로 막힌다. 400 을 받은 세션은 `CONSENTED` 를 유지하므로 그대로 다시 찍으면 된다.

### `POST /api/mirror/sessions/{sessionId}/assist-request`

직원 도움 받기. 직원 화면 대기 목록에 올라간다.

### `POST /api/mirror/sessions/{sessionId}/self-browse`

혼자 볼게요. **직원 알림을 보내지 않는다.** 다만 직원 목록에는 '응대 불필요' 상태로
계속 보인다 — 목록에서 아예 빼면 직원이 그 미러를 비어 있는 것으로 오해한다.

자율 관람 중에도 마음이 바뀌면 다시 `assist-request` 를 호출할 수 있다.

### `POST /api/mirror/sessions/{sessionId}/assist-cancel`

도움 요청 철회. 이미 직원이 응대를 시작한 뒤라면 409 이며, 그때는 직원 쪽의
`release` 로 풀어야 한다.

### `POST /api/mirror/sessions/{sessionId}/end`

고객이 경험을 마친다. 세션을 `ENDED` 로 닫으며 **완주로 기록된다.**
무입력 타임아웃·중단으로 끝난 `EXPIRED` 와 구분되고, 이 구분이 완주 세션 지표를 만든다.

### `POST /api/mirror/sessions/{sessionId}/reset`

대기 화면으로 복귀. 미응대 알림도 함께 정리된다.

> **프론트의 무입력 타임아웃은 반드시 이 경로를 호출해야 한다.** 화면만 초기화하면
> 서버 세션이 남아 직원 대기 목록에 계속 떠 있다.

---

## 직원 응대 — `/api/staff`

> 실서비스에서는 여기에 직원 인증이 붙어야 한다. 현재는 사내망·데모 전제라 인증이
> 없고 `staffId` 는 자기 신고값이다.

### `GET /api/staff/sessions`

직원 화면이 폴링하는 경로. 도움을 요청한 세션이 위로, 오래 기다린 순으로 온다.

```json
[{
  "sessionId": "01KZX8...",
  "mirrorId": "mirror-01",
  "mirrorLabel": "2F 피팅룸 A",
  "state": "ASSIST_REQUESTED",
  "elapsedSeconds": 42,
  "waitingSeconds": 18,
  "conceptName": "Berlin Blue Hour",
  "needsAssist": true,
  "assignedStaffId": null
}]
```

- **추천 제품은 이 응답에 없다.** 추천은 호출마다 LLM 랭킹이 도는 비싼 연산이라 폴링
  경로에 얹을 수 없다. 카드를 열 때 받는다.
- `waitingSeconds` 가 60 을 넘으면 서버가 SSE 로 재알림한다. 같은 세션에 대해 60초에
  한 번씩만 울린다.

### `GET /api/staff/sessions/{sessionId}`

응대 카드. 직원이 고객에게 걸어가면서 보는 화면이다.

```json
{
  "sessionId": "01KZX8...", "mirrorLabel": "2F 피팅룸 A",
  "state": "ASSIST_ACCEPTED", "elapsedSeconds": 120, "waitingSeconds": 35,
  "conceptName": "Berlin Blue Hour",
  "mood": "elegant-night",
  "palette": ["black", "cognac"],
  "style": "street-luxury",
  "formality": 0.6,
  "styleComment": "시크한 올블랙에 코냑 포인트 — 밤의 뮌헨이 어울리는 룩.",
  "recommendations": [
    { "category": "bag", "items": [
      { "rank": 1, "productId": "MCM-BAG-014", "reason": "올블랙 착장에 코냑 Visetos가 유일한 포인트로 들어옵니다.",
        "product": { "name": "...", "priceKrw": 1150000, "imageUrl": "...", "storeLocation": "1F 백팩 존" } }
    ]},
    { "category": "wallet", "items": [ ... ] },
    { "category": "belt",   "items": [ ... ] }
  ],
  "stylingNote": "코냑 포인트를 가방 하나로 몰아주고 나머지는 블랙으로 정리하는 조합을 권합니다.",
  "analysisFallback": false,
  "recommendationFallback": false,
  "recommendationsReady": true,
  "note": null,
  "assignedStaffId": "staff-01"
}
```

- **무드·팔레트·컨셉명이 추천과 한 응답에 들어간다.** 추천만 내려가면 직원 화면이
  고객의 무드를 알 방법이 없다.
- `recommendationFallback: true` 면 LLM 랭킹이 실패해 프리필터 점수로 대체된 것이다.
  이때 추천 이유는 템플릿 문장이므로 **직원이 그대로 읽으면 안 된다.**
- 선정된 제품은 카탈로그와 대조되므로 존재하지 않는 제품은 나오지 않는다.
- **원본 분석 이미지는 포함하지 않는다.** 직원에게 가는 것은 분석 결과 텍스트뿐이다.

#### `recommendationsReady` — 카드는 추천을 기다리지 않는다

`false` 면 `recommendations` 가 비어 있고, **잠시 뒤 같은 경로를 다시 조회하면 채워진다.**
LLM 랭킹이 실측 5초라 이걸 기다렸다 카드를 내려주면 직원이 그동안 빈 화면을 본다.
무드·팔레트는 고객에게 걸어가면서 당장 필요한 정보이므로 먼저 나간다.

계산은 **고객이 도움을 요청하는 순간** 서버가 미리 시작한다. 직원이 알림을 보고 손을
뻗기까지의 몇 초에 계산이 끼어들어가므로, 실제로는 대부분 `true` 로 도착한다.

| 직원 반응 시간 | 카드 열림 | 추천 도착 |
|---|---|---|
| 8초 | 7ms | 이미 준비됨 |
| 4초 | 15ms | +0.8초 |
| 0초 (최악) | 4ms | +6초 |

> 조회 자체가 계산을 다시 걸어주므로 예열이 실패해도 폴링이 복구한다.
> 같은 세션에 대해 중복 실행되지 않으니 폴링해도 한도를 쓰지 않는다.

**추천은 세션당 한 번만 계산하고 이후에는 캐시에서 나간다.** 같은 착장의 추천이 바뀔
이유가 없고, 매번 계산하면 무료 등급 분당 3회 한도를 즉시 넘긴다.

### `POST /api/staff/sessions/{sessionId}/accept`

```json
{ "staffId": "staff-01" }
```

**중복 응대 방지 지점이다.**

- **직원 이름은 받지 않는다.** 이름의 유일한 용도가 고객 화면 문구였는데 그 문구가
  상태 기반으로 바뀌었다. 남기면 응대마다 입력을 강요하고, 검증되지 않은 자기 신고값이
  고객 화면까지 흘러간다
- 이미 다른 직원이 점유 중이면 **409 `assist_conflict`** (응답 메시지에 점유자를 밝히지 않는다)
- 같은 직원의 재요청은 버튼 중복 클릭이므로 조용히 성공한다 (멱등)
- **'혼자 볼게요' 세션은 409 `illegal_state`** — 고객이 거절한 응대를 직원이 밀어붙일 수 없다
- 점유되면 고객 화면의 `musicDucked` 가 true 로 바뀐다

### `POST /api/staff/sessions/{sessionId}/release`

```json
{ "staffId": "staff-01" }
```

점유를 놓는다. 세션은 다시 대기 목록으로 돌아가 다른 직원이 받을 수 있다.
**점유자 본인만 해제할 수 있다.**

### `POST /api/staff/sessions/{sessionId}/complete`

```json
{ "staffId": "staff-01" }
```

**세션을 끝내지 않는다.** 응대만 닫고 상태를 `MOOD_ACTIVE` 로 되돌린다.
담당 직원이 해제되고 직원 대기 목록에서는 빠지지만, 고객의 연출은 그대로 유지된다.

직원의 일이 끝난 것이지 고객의 경험이 끝난 것이 아니다. 세션을 끝내는 것은
고객의 `POST /api/mirror/sessions/{id}/end` 와 무입력 타임아웃뿐이다.

`staffId` 는 선택이며, 넣으면 점유자 본인인지 확인한다. 다른 직원이 점유 중이면 409.

추천 캐시는 지우지 않는다 — 고객이 다시 도움을 부르면 같은 착장의 추천이 필요한데,
지우면 LLM 랭킹이 한 번 더 돌아 분당 한도를 쓴다.

### `GET /api/staff/notifications` — SSE

직원 단말이 상태 변화를 실시간으로 받는 스트림. `Content-Type: text/event-stream`.

**WebSocket 이 아니라 SSE 인 이유**: 이 채널은 서버 → 직원 단방향이다. 직원의 동작은
이미 REST 로 올라가므로 양방향이 필요 없다. SSE 는 일반 HTTP 라 매장 방화벽·프록시를
그대로 통과하고, 끊기면 브라우저가 알아서 재연결한다.

| `event` | 언제 | 직원 화면이 할 일 |
|---|---|---|
| `connected` | 구독 성공 | 현재 대기 건수(`waiting`) 표시 |
| `assist_requested` | 새 도움 요청 | 목록에 추가 + 알림음 |
| `assist_cancelled` | 고객이 철회 | 목록에서 제거 |
| `assist_accepted` | 다른 직원이 점유 | **잠금 표시** |
| `assist_released` | 직원이 응대를 놓음 | 다시 대기로 |
| `assist_finished` | 직원이 응대를 마침 | 목록에서 제거 (세션은 살아 있음) |
| `self_browsing` | 고객이 혼자 보기 선택 | 응대 불필요 표시 |
| `session_closed` | 세션 종료 | 목록에서 제거 |

```
event: assist_requested
data: {"type":"ASSIST_REQUESTED","sessionId":"01KZX8...","mirrorId":"mirror-01",
       "mirrorLabel":"2F 피팅룸 A","state":"ASSIST_REQUESTED",
       "waitingSeconds":0,"reminder":false,"occurredAt":"2026-08-17T10:00:00Z"}
```

- **알림에는 제품 추천이 실리지 않는다.** 추천은 세션당 한 번 LLM 랭킹을 돌려야 하는
  비싼 연산이라 알림마다 만들면 한도를 즉시 넘긴다. 상세는 카드를 열 때 가져간다.
- `reminder: true` 는 **60초 넘게 미확인이라 다시 보낸 것**이다. 같은 세션에 대해
  60초에 한 번씩만 울린다.
- 고객의 앞단 진행(동의·분석)은 알림을 만들지 않는다. 대기 화면이 소음으로 차지 않도록.
- 15초마다 `:ping` 주석이 온다. 프록시가 유휴 연결을 끊는 것을 막기 위한 것이며
  이벤트 핸들러에는 잡히지 않는다.

> **이 스트림이 막혀도 직원 화면은 동작한다.** `GET /api/staff/sessions` 폴링이
> 폴백으로 그대로 남아 있다.

---

## 공통 — `/api/health`

```json
{ "status": "ok", "llmMode": "live", "musicMode": "jamendo", "activeSessions": 2 }
```

| 필드 | 값 | 의미 |
|---|---|---|
| `llmMode` | `live` / `mock` | OpenAI 키 유무 |
| `musicMode` | `jamendo` / `synth` | 음원 검색 가능 여부 |

> **`live` 는 "키가 들어왔다"는 뜻이지 "키가 유효하다"는 뜻이 아니다.** 잘못된 키도
> `live` 로 표시되고 호출 시점에 401 이 난다. 실제 성공 여부는 서버 로그의
> `analyze done ... fallback=false` 로 확인한다.

---

## 오류 응답

모든 오류는 같은 모양이다. 변환은 `ApiExceptionHandler` 한 곳에서만 일어난다.

```json
{ "error": "assist_conflict", "message": "이미 김직원 님이 응대 중인 고객입니다." }
```

| HTTP | `error` | 발생 조건 | 프론트가 할 일 |
|---|---|---|---|
| 400 | `bad_request` | 필수값 누락, base64 디코딩 실패 | 입력 수정 후 재시도 |
| 404 | `session_not_found` | 세션 만료 또는 없음 | 처음부터 다시 시작 |
| 404 | `not_found` | 존재하지 않는 경로 | — |
| 405 | `method_not_allowed` | 잘못된 HTTP 메서드 | — |
| 409 | `illegal_state` | 현재 상태에서 허용되지 않는 전이 | **세션 상태를 다시 조회해 화면을 맞춘다** |
| 409 | `assist_conflict` | 다른 직원이 점유 중 | "누가 응대 중"임을 안내 |
| 500 | `internal_error` | 그 외 | 재시도 |

`illegal_state` 와 `assist_conflict` 를 나눈 이유는 직원 화면이 "누가 응대 중입니다"와
"지금은 응대할 수 없는 상태입니다"를 다른 문구로 띄워야 하기 때문이다.

> 예외 메시지에 이미지 원문이나 내부 경로가 실리지 않도록, 서비스 계층에서 정제한
> 메시지만 내보낸다.

---

## 아직 없는 것

| 항목 | 현재 | 필요한 것 |
|---|---|---|
| **직원 인증** | 없음. `staffId` 는 자기 신고값 | 매장 직원 역할 검증 |
| 세션 영속화 | 인메모리. 재시작하면 사라짐 | Redis 또는 DB |
| 이벤트 영속화 | 인메모리 로그 | `session_events` 테이블 |
| 다중 인스턴스 | SSE 구독이 인스턴스별로 갈림 | 메시지 브로커 |

인증이 없는 것이 가장 큰 구멍이다. 지금은 아무나 남의 `staffId` 로 점유를 가로챌 수
있고, `/api/staff/**` 를 알기만 하면 고객 분석 결과와 추천을 그대로 읽을 수 있다.
사내망·데모 전제라 미룬 것이며 매장 도입 전에는 반드시 붙어야 한다.
