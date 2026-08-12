package com.example.back.service;

import com.example.back.dto.ApiPayloads.RecommendResponse;
import org.springframework.stereotype.Service;

@Service
public class StaffService {

    private final MirrorService mirrorService;

    public StaffService(MirrorService mirrorService) {
        this.mirrorService = mirrorService;
    }

    public RecommendResponse getStaffSession(String sessionId) {
        return mirrorService.recommend(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(
                        "세션이 만료되었거나 존재하지 않습니다. 다시 촬영해 주세요."
                ));
    }
}