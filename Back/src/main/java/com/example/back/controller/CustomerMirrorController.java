package com.example.back.controller;

import com.example.back.dto.ApiPayloads.AnalyzeRequest;
import com.example.back.dto.ApiPayloads.AnalyzeResponse;
import com.example.back.dto.ApiPayloads.ErrorResponse;
import com.example.back.dto.ApiPayloads.SessionStateResponse;
import com.example.back.dto.ApiPayloads.StartSessionRequest;
import com.example.back.dto.ApiPayloads.StartSessionResponse;
import com.example.back.service.CustomerSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 미러 디스플레이 전용 API.
 * <p>
 * <b>이 컨트롤러에는 제품 추천을 돌려주는 엔드포인트가 없다.</b> 고객 화면에 AI 추천을
 * 노출하지 않는 것이 이 서비스의 유일한 차별점인데, 고객이 도달 가능한 경로에 추천
 * 엔드포인트가 하나라도 남아 있으면 그 원칙은 "프론트가 안 부르기로 한 약속"으로 떨어진다.
 * 추천은 {@code /api/staff/**} 로만 나간다.
 * <p>
 * 응답 조립은 {@link CustomerSessionService} 가 하고, 여기서는 경로와 문서만 맡는다.
 * 예외 → HTTP 상태 변환은 {@link ApiExceptionHandler} 가 공통으로 처리한다.
 */
@RestController
@RequestMapping("/api/mirror")
@Tag(name = "1. 고객 미러", description = "미러 디스플레이 — 세션 · 동의 · 분석 · 연출 · 응대 선택")
public class CustomerMirrorController {

    private final CustomerSessionService customer;

    public CustomerMirrorController(CustomerSessionService customer) {
        this.customer = customer;
    }

    // ------------------------------------------------------------- 세션 생명주기

    @PostMapping("/sessions")
    @Operation(
            summary = "세션 시작",
            description = """
                    미러 대기 화면에서 고객이 반응하면 세션을 연다.

                    분석보다 먼저 세션을 여는 이유는 두 가지다. 직원 알림에 어느 미러인지가
                    필요하고, 동의를 거부했거나 중간에 이탈한 고객도 지표에 남겨야 한다.

                    `mirrorId` 와 `storeId` 는 필수다. 어느 미러인지 모르는 세션은
                    직원 화면에 띄울 수 없으므로 400 으로 거절한다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세션 생성됨. 상태는 `IDLE`",
                    content = @Content(schema = @Schema(implementation = StartSessionResponse.class))),
            @ApiResponse(responseCode = "400", description = "mirrorId 또는 storeId 누락",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StartSessionResponse start(@RequestBody StartSessionRequest request) {
        return customer.start(request);
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "세션 상태 조회", description = """
            미러 화면이 폴링하는 경로. 연출 상태와 직원 응대 여부를 함께 돌려준다.

            `musicDucked` 가 true 면 직원 응대가 시작된 것이므로 음악 볼륨을 낮추고
            조명 연출은 유지한다. 클라이언트가 상태값으로 추론하지 않도록 서버가 직접 알려준다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = SessionStateResponse.class))),
            @ApiResponse(responseCode = "404", description = "세션 만료 또는 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public SessionStateResponse state(
            @Parameter(description = "세션 시작 응답의 sessionId", required = true)
            @PathVariable String sessionId) {
        return customer.state(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/consent")
    @Operation(summary = "촬영·분석 동의", description = """
            동의 이후 프론트가 3·2·1 카운트다운을 진행한다.

            카운트다운은 서버 상태로 두지 않았다. 서버는 카운트다운을 관측할 수단이 없고
            이미지가 도착해야 비로소 알 수 있으므로, 클라이언트 UI 단계로 남긴다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상태가 `CONSENTED` 로 이동",
                    content = @Content(schema = @Schema(implementation = SessionStateResponse.class))),
            @ApiResponse(responseCode = "409", description = "현재 상태에서 허용되지 않는 전이",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public SessionStateResponse consent(@PathVariable String sessionId) {
        return customer.consent(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/reset")
    @Operation(summary = "세션 리셋", description = """
            미러를 대기 화면으로 되돌린다. 미응대 알림이 걸려 있으면 함께 취소된다.

            프론트의 무입력 타임아웃도 이 경로를 호출해야 한다. 화면만 초기화하면
            서버 세션이 남아 직원 대기 목록에 계속 떠 있다.
            """)
    public SessionStateResponse reset(@PathVariable String sessionId) {
        return customer.reset(sessionId);
    }

    // ----------------------------------------------------------------- 분석·연출

    @PostMapping("/sessions/{sessionId}/analyze")
    @Operation(
            summary = "착장 분석 + 조명·음향 생성",
            description = """
                    촬영 이미지를 받아 조명 스펙·음향 스펙·재생할 음원을 돌려준다.

                    내부적으로 LLM 을 2회 호출한다 (Vision 분석 → 후보 곡 중 선곡).
                    응답 시간은 실측 5~7초.

                    **이미지는 저장되지 않는다.** 메모리에서만 처리하고 폐기하며,
                    세션에 남는 것은 분석 결과 텍스트뿐이다.

                    LLM 이 실패해도 200 을 돌려준다. 이때 `fallback: true` 이고
                    사전 정의 프리셋 5종 중 하나가 적용된다 (이미지 해시 기준이라 같은 사진은 같은 결과).
                    고객 경험은 성공과 동일해야 하므로 화면에는 차이를 드러내지 않는다.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "분석 성공. `fallback` 값으로 AI 결과인지 프리셋인지 구분할 것",
                    content = @Content(schema = @Schema(implementation = AnalyzeResponse.class))),
            @ApiResponse(responseCode = "400", description = "image 누락 또는 base64 디코딩 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "세션 만료 또는 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "동의 전이거나 이미 분석이 끝난 세션",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public AnalyzeResponse analyze(
            @PathVariable String sessionId,
            @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "`image` 는 `data:image/jpeg;base64,...` 형태의 data URL",
                    required = true)
            AnalyzeRequest request) {
        return customer.analyze(sessionId, request == null ? null : request.image());
    }

    // --------------------------------------------------------------- 고객의 선택

    @PostMapping("/sessions/{sessionId}/assist-request")
    @Operation(summary = "직원 도움 받기", description = """
            직원 화면 대기 목록에 이 세션이 올라간다.

            고객 화면에는 여전히 추천이 표시되지 않는다. 직원이 응대를 시작하면
            상태 조회 응답의 `musicDucked` 가 true 로 바뀐다.
            """)
    public SessionStateResponse requestAssist(@PathVariable String sessionId) {
        return customer.requestAssist(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/self-browse")
    @Operation(summary = "혼자 볼게요", description = """
            직원 알림을 보내지 않는다.

            직원 목록에는 '응대 불필요' 상태로 계속 보인다. 목록에서 아예 빼면
            직원이 그 미러를 비어 있는 것으로 오해한다.

            자율 관람 중에도 마음이 바뀌면 다시 `assist-request` 를 호출할 수 있다.
            """)
    public SessionStateResponse browseAlone(@PathVariable String sessionId) {
        return customer.browseAlone(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/assist-cancel")
    @Operation(summary = "도움 요청 철회", description = """
            직원 대기 목록에서 내려간다.

            이미 직원이 응대를 시작한 뒤라면 409 가 난다. 그때는 직원 쪽의
            `release` 로 풀어야 한다.
            """)
    public SessionStateResponse cancelAssist(@PathVariable String sessionId) {
        return customer.cancelAssist(sessionId);
    }
}
