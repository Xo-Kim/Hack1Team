package com.example.back.domain;

import java.time.Instant;
import java.util.Objects;

/** 직원 BE 가 콜백으로 통지한 응대 담당자. 잠금의 소유권은 직원 BE 에 있다. */
public record StaffAssignment(String staffId, String staffName, Instant acceptedAt) {

    public StaffAssignment {
        Objects.requireNonNull(staffId, "staffId");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
