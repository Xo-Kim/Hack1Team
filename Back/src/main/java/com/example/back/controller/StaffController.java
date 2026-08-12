package com.example.back.controller;

import com.example.back.dto.ApiPayloads.ErrorResponse;
import com.example.back.dto.ApiPayloads.RecommendResponse;
import com.example.back.service.SessionNotFoundException;
import com.example.back.service.StaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/staff")
@Tag(name = "Staff", description = "직원용 응대 카드 API")
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
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
    public ResponseEntity<RecommendResponse> getStaffSession(
            @Parameter(
                    description = "분석 API에서 발급된 sessionId",
                    required = true
            )
            @PathVariable String sessionId
    ) {
        RecommendResponse response = staffService.getStaffSession(sessionId);
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> sessionNotFound(
            SessionNotFoundException e
    ) {
        return ResponseEntity.status(404)
                .body(new ErrorResponse(
                        "session_not_found",
                        e.getMessage()
                ));
    }
}