package com.example.back.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 응대를 점유한 직원. 잠금의 소유권은 직원 단말에 있다.
 * <p>
 * <b>이름을 담지 않는다.</b> 이름의 유일한 용도는 고객 화면에 "○○ 님이 곧 도착합니다"를
 * 띄우는 것이었는데, 그러려면 직원이 매번 자기 이름을 손으로 입력해야 했고 그 값은
 * 아무도 검증하지 않는 자기 신고값이었다. 고객이 얻는 것은 "누가 오는가"가 아니라
 * "곧 온다"는 사실이므로, 검증되지 않은 개인정보를 고객 화면까지 흘려보내는 대신
 * 안내 문구를 상태 기반으로 바꿨다. 직원 식별은 {@code staffId} 로 충분하다.
 */
public record StaffAssignment(String staffId, Instant acceptedAt) {

    public StaffAssignment {
        Objects.requireNonNull(staffId, "staffId");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
