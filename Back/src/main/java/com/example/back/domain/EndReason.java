package com.example.back.domain;

/** 직원 BE 의 /internal/v1/sessions/{id}/ended 페이로드에 실리는 종료 사유. */
public enum EndReason {
    COMPLETED, RESET, TIMEOUT
}
