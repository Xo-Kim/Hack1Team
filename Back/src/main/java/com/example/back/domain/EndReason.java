package com.example.back.domain;

/** 세션이 어떻게 끝났는지. 완주와 이탈을 지표에서 구분하기 위해 남긴다. */
public enum EndReason {
    COMPLETED, RESET, TIMEOUT
}
