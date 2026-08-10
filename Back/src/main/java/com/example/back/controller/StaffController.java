package com.example.back.controller;


import com.example.back.dto.ApiPayloads.ErrorResponse;
import com.example.back.dto.ApiPayloads.RecommendResponse;
import com.example.back.service.MirrorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/staff")
@Tag(name = "Staff", description = "직원용 응대 카드 API")
public class StaffController {

    private final MirrorService mirror;

    public StaffController(MirrorService mirror) {
        this.mirror = mirror;
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(
            summary = "직원용 응대 카드 조회",
            description = """
                    고객 세션의 분석 결과와 제품 추천을 직원 화면에서 조회한다.

                    고객 화면에는 추천 결과를 보여주지 않고,
                    직원 화면에서만 이 API를 통해 응대 카드를 확인한다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "응대 카드 조회 성공",
                    content = @Content(
                            schema = @Schema(implementation = RecommendResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "세션이 없거나 만료됨",
                    content = @Content(
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    public ResponseEntity<?> getStaffSession(
            @Parameter(
                    description = "분석 API에서 발급된 sessionId",
                    required = true
            )
            @PathVariable String sessionId
    ) {
        return mirror.recommend(sessionId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse(
                                "session_not_found",
                                "세션이 만료되었거나 존재하지 않습니다."
                        )));
    }
}
