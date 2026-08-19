package com.example.back.dto;

import java.util.List;

/**
 * <b>직원 화면(/api/staff/**)이 주고받는 페이로드.</b>
 * <p>
 * 고객 화면 페이로드({@link ApiPayloads})와 파일을 분리해 둔다. 추천·분석 원문이
 * 실리는 쪽은 여기 하나뿐이어야 하고, 어떤 필드를 어느 화면이 보는지가
 * import 문만 봐도 드러나야 한다.
 */
public final class StaffPayloads {

    private StaffPayloads() {
    }

    /**
     * 대기 목록의 한 줄. 카드를 열기 전에 보이는 정보만 담는다.
     * <p>
     * 추천 제품이 여기 없는 것은 의도다. 목록은 3초 간격으로 폴링되는데
     * 추천은 호출마다 LLM 랭킹이 도는 비싼 연산이라, 목록에 얹으면
     * 무료 등급 분당 3회 한도를 즉시 넘긴다.
     *
     * @param waitingSeconds 도움을 요청한 뒤 흐른 시간. 60초를 넘으면 재알림 대상이다.
     *                       요청 상태가 아니면 null
     * @param needsAssist    직원이 가야 하는 세션인지. '혼자 볼게요'는 false 로 내려가
     *                       목록에는 보이되 응대 대상에서는 빠진다
     * @param assignedStaffId 점유 중인 직원. 단말은 이 값이 자기 것인지만 보면 되므로
     *                        이름은 내려가지 않는다 ({@link com.example.back.domain.StaffAssignment} 참고)
     */
    public record StaffSessionSummary(
            String sessionId,
            String mirrorId,
            String mirrorLabel,
            String state,
            long elapsedSeconds,
            Long waitingSeconds,
            String conceptName,
            boolean needsAssist,
            String assignedStaffId
    ) {
    }

    /**
     * 응대 카드. 직원이 고객에게 걸어가면서 보는 화면이다.
     * <p>
     * 무드·팔레트·컨셉명이 추천과 <b>한 응답에</b> 들어간다. 예전에는 추천만
     * 내려가서 직원 화면이 무드를 알 방법이 없었다.
     * <p>
     * 원본 분석 이미지와 촬영 사진은 포함하지 않는다. 직원에게 가는 것은
     * 분석 결과 텍스트뿐이다.
     *
     * @param styleComment           고객 화면에도 보이는 연출 문구. 대화를 열 때 쓴다
     * @param recommendationFallback LLM 랭킹이 실패해 프리필터 점수로 대체됐는지.
     *                               true 면 추천 이유가 템플릿 문장이므로 직원이 그대로 읽으면 안 된다
     * @param recommendationsReady   추천 계산이 끝났는지. <b>false 면 {@code recommendations}
     *                               가 비어 있고, 잠시 뒤 다시 조회하면 채워진다.</b> 랭킹은 실측
     *                               5초라 이걸 기다렸다 카드를 내려주면 직원이 그동안 빈 화면을
     *                               본다. 무드·팔레트는 걸어가면서 바로 필요한 정보라 먼저 보낸다
     */
    public record StaffCard(
            String sessionId,
            String mirrorId,
            String mirrorLabel,
            String state,
            long elapsedSeconds,
            Long waitingSeconds,
            String conceptName,
            String mood,
            List<String> palette,
            String style,
            Double formality,
            String styleComment,
            List<CategoryRecommendation> recommendations,
            String stylingNote,
            boolean analysisFallback,
            boolean recommendationFallback,
            boolean recommendationsReady,
            String note,
            String assignedStaffId
    ) {
    }

    /**
     * 응대 시작. staffId 는 중복 응대 판정의 기준이라 필수다.
     * <p>
     * 직원 이름은 받지 않는다. 단말에 이름 입력란을 두면 매 응대마다 손으로 적어야 하고
     * 그 값은 검증되지 않은 채 고객 화면까지 흘러갔다. 고객 안내는 상태 기반으로 바뀌었다.
     */
    public record AcceptAssistRequest(String staffId) {
    }

    /** 응대 해제. 점유자 본인인지 확인하는 데만 쓴다. */
    public record ReleaseAssistRequest(String staffId) {
    }

    /**
     * 응대 완료. {@code staffId} 는 선택이며, 넣으면 점유자 본인인지 확인한다.
     * 점유 없이 남은 세션을 직원이 정리하는 경우가 있어 필수로 두지 않았다.
     */
    public record CompleteSessionRequest(String staffId) {
    }
}
