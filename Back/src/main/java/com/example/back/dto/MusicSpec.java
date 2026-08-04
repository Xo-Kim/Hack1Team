package com.example.back.dto;

import java.util.List;

/**
 * 무드 기반 음향 스펙.
 * <p>
 * queryTags/genreHint 는 향후 Spotify 검색으로 연결하기 위한 필드이고,
 * key/scale/bpm/energy 는 프론트의 Web Audio 절차적 생성기가 직접 소비한다.
 * 재생 수단이 바뀌어도 이 스펙은 유지된다.
 */
public record MusicSpec(
        List<String> queryTags,
        double energy,
        String genreHint,
        String key,
        String scale,
        int bpm
) {
}
