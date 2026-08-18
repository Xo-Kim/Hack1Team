package com.example.back.controller;

import com.example.back.dto.ApiPayloads.ErrorResponse;
import com.example.back.dto.StaffPayloads.AcceptAssistRequest;
import com.example.back.dto.StaffPayloads.CompleteSessionRequest;
import com.example.back.dto.StaffPayloads.ReleaseAssistRequest;
import com.example.back.dto.StaffPayloads.StaffCard;
import com.example.back.dto.StaffPayloads.StaffSessionSummary;
import com.example.back.service.StaffNotifier;
import com.example.back.service.StaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 직원 태블릿/모바일 전용 API.
 * <p>
 * <b>제품 추천이 시스템 밖으로 나가는 유일한 통로다.</b> 고객 미러 화면은
 * {@code /api/mirror/**} 만 호출하며 이 경로에 접근할 이유가 없다.
 * CORS 도 고객 프론트 출처에는 {@code /api/mirror/**} 만 열려 있다
 * ({@code WebConfig} 참고).
 * <p>
 * 실서비스에서는 여기에 직원 인증이 붙어야 한다. 지금은 사내망·데모 전제라
 * 인증이 없고, {@code staffId} 는 자기 신고값이다.
 */
@RestController
@RequestMapping("/api/staff")
@Tag(name = "2. 직원 응대", description = "직원 단말 — 대기 목록 · 응대 카드 · 응대 점유")
public class StaffController {

    private final StaffService staff;
    private final StaffNotifier notifier;

    public StaffController(StaffService staff, StaffNotifier notifier) {
        this.staff = staff;
        this.notifier = notifier;
    }

    @GetMapping("/sessions")
    @Operation(
            summary = "응대 대기 목록",
            description = """
                    직원 화면이 폴링하는 경로. 도움을 요청한 세션이 위로, 오래 기다린 순으로 온다.

                    응대 중(`ASSIST_ACCEPTED`)이거나 '혼자 볼게요'(`SELF_BROWSING`)인 세션도
                    함께 내려가지만 `needsAssist` 는 false 다. 응대 불필요 세션을 목록에서
                    빼버리면 직원이 그 미러를 비어 있는 것으로 오해한다.

                    **추천 제품은 이 응답에 없다.** 추천은 호출마다 LLM 랭킹이 도는 비싼
                    연산이라 폴링 경로에 얹을 수 없다. 카드를 열 때 받는다.

                    `waitingSeconds` 가 60 을 넘으면 재알림 대상이다. 재알림 자체는
                    아직 서버가 하지 않으므로 현재는 직원 화면이 이 값으로 판단해야 한다.
                    """)
    @ApiResponses(@ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(array = @ArraySchema(
                    schema = @Schema(implementation = StaffSessionSummary.class)))))
    public List<StaffSessionSummary> list() {
        return staff.list();
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(
            summary = "응대 카드 조회",
            description = """
                    직원이 고객에게 걸어가면서 보는 화면. 무드·팔레트·컨셉명과
                    카테고리별(가방·지갑·벨트) 추천 3종씩을 한 번에 돌려준다.

                    선정된 제품은 카탈로그와 대조되므로 존재하지 않는 제품은 나오지 않는다.
                    LLM 랭킹이 실패하면 프리필터 점수 상위 3종으로 대체하고
                    `recommendationFallback: true` 로 알린다. 이때 추천 이유는 템플릿
                    문장이므로 직원이 그대로 읽으면 안 된다.

                    추천은 세션당 한 번만 계산하고 이후에는 캐시에서 나간다.
                    같은 착장의 추천이 바뀔 이유가 없고, 매번 계산하면 무료 등급
                    분당 3회 한도를 넘긴다.

                    **원본 분석 이미지와 촬영 사진은 포함하지 않는다.** 직원에게 가는 것은
                    분석 결과 텍스트뿐이다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = StaffCard.class))),
            @ApiResponse(responseCode = "404", description = "세션 만료 또는 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StaffCard card(
            @Parameter(description = "대기 목록의 sessionId", required = true)
            @PathVariable String sessionId) {
        return staff.card(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/accept")
    @Operation(
            summary = "응대 시작 (점유)",
            description = """
                    이 세션의 응대 권한을 가져온다. **중복 응대 방지 지점이다.**

                    이미 다른 직원이 점유 중이면 409 `assist_conflict` 로 막힌다.
                    같은 직원의 재요청은 버튼 중복 클릭이므로 조용히 성공한다(멱등).

                    '혼자 볼게요'를 선택한 세션은 409 `illegal_state` 가 난다.
                    고객이 거절한 응대를 직원이 밀어붙일 수 없다.

                    점유되면 고객 미러 화면의 상태 조회 응답에서 `musicDucked` 가
                    true 로 바뀌어 음악 볼륨이 내려간다. 조명 연출은 유지된다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "점유 성공. 갱신된 응대 카드",
                    content = @Content(schema = @Schema(implementation = StaffCard.class))),
            @ApiResponse(responseCode = "400", description = "staffId 누락",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "세션 만료 또는 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409",
                    description = "다른 직원이 응대 중(`assist_conflict`) 또는 허용되지 않는 전이(`illegal_state`)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StaffCard accept(@PathVariable String sessionId,
                            @RequestBody AcceptAssistRequest request) {
        return staff.accept(sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/release")
    @Operation(
            summary = "응대 해제",
            description = """
                    점유를 놓는다. 세션은 다시 대기 목록으로 돌아가 다른 직원이 받을 수 있다.

                    점유자 본인만 해제할 수 있다. 다른 직원이 시도하면 409 다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "해제 성공",
                    content = @Content(schema = @Schema(implementation = StaffCard.class))),
            @ApiResponse(responseCode = "409", description = "점유자가 아니거나 응대 중이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StaffCard release(@PathVariable String sessionId,
                             @RequestBody ReleaseAssistRequest request) {
        return staff.release(sessionId, request == null ? null : request.staffId());
    }

    @PostMapping("/sessions/{sessionId}/complete")
    @Operation(
            summary = "응대 완료 (세션 정상 종료)",
            description = """
                    세션을 `ENDED` 로 닫는다. 타임아웃으로 끝난 `EXPIRED` 와 구분된다.

                    **이 구분이 핵심 지표를 만든다.** 완주 세션 수가 North Star 인데
                    종료 경로가 타임아웃 하나뿐이면 "응대를 마친 고객"과 "그냥 떠난 고객"이
                    같은 값으로 집계된다.

                    `staffId` 는 선택이다. 넣으면 점유자 본인인지 확인하고, 비우면
                    확인 없이 닫는다 — 고객이 혼자 보다 그냥 간 세션을 직원이 정리하는
                    경우가 있어 필수로 두지 않았다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "종료됨. `state` 는 `ENDED`",
                    content = @Content(schema = @Schema(implementation = StaffCard.class))),
            @ApiResponse(responseCode = "404", description = "세션 만료 또는 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "다른 직원이 점유 중",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StaffCard complete(@PathVariable String sessionId,
                              @RequestBody(required = false) CompleteSessionRequest request) {
        return staff.complete(sessionId, request == null ? null : request.staffId());
    }

    @GetMapping(value = "/notifications", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "실시간 알림 구독 (SSE)",
            description = """
                    직원 단말이 상태 변화를 실시간으로 받는 스트림이다.

                    **WebSocket 이 아니라 SSE 인 이유**: 이 채널은 서버 → 직원 단방향이다.
                    직원의 동작(응대 시작·해제)은 이미 REST 로 올라가므로 양방향이 필요 없다.
                    SSE 는 일반 HTTP 라 매장 방화벽·프록시를 그대로 통과하고, 끊기면
                    브라우저가 알아서 재연결한다.

                    ### 이벤트 종류
                    | `event` | 의미 |
                    |---|---|
                    | `connected` | 구독 성공. 현재 대기 건수를 함께 보낸다 |
                    | `assist_requested` | 새 도움 요청. `reminder: true` 면 60초 초과 재알림 |
                    | `assist_cancelled` | 고객이 요청을 철회 |
                    | `assist_accepted` | 다른 직원이 응대 시작 — 중복 응대 방지 표시 |
                    | `assist_released` | 직원이 응대를 놓음. 다시 대기열로 |
                    | `self_browsing` | 고객이 혼자 보기를 선택. 접근하지 말 것 |
                    | `session_closed` | 세션 종료. 알림을 내릴 것 |

                    **알림에는 제품 추천이 실리지 않는다.** 추천은 세션당 한 번 LLM 랭킹을
                    돌려야 하는 비싼 연산이라 알림마다 만들면 한도를 즉시 넘긴다.
                    상세는 응대 카드를 열 때 가져간다.

                    15초마다 주석(`:ping`)이 오는데 프록시가 유휴 연결을 끊는 것을 막기
                    위한 것이며 이벤트 핸들러에는 잡히지 않는다.

                    **이 스트림이 막혀도 직원 화면은 동작한다.** `GET /api/staff/sessions`
                    폴링이 폴백으로 그대로 남아 있다.
                    """)
    public SseEmitter notifications() {
        return notifier.subscribe();
    }
}
