package com.example.back.service;

import com.example.back.config.MoodMirrorProperties;
import com.example.back.dto.MoodAnalysis;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인메모리 세션 저장소.
 * <p>
 * <b>이미지는 여기에 들어오지 않는다.</b> 분석 결과 텍스트만 보관한다. (PRD §16)
 * 1차 분석과 2차 추천을 두 번의 요청으로 나누기 위해서만 존재하며,
 * TTL 이 지나면 조회 시점에 즉시 폐기된다.
 */
@Component
public class SessionStore {

    public record Entry(MoodAnalysis analysis, boolean analysisFallback, Instant createdAt) {
    }

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final Duration ttl;

    public SessionStore(MoodMirrorProperties props) {
        int minutes = props.session() == null ? 15 : props.session().ttlMinutes();
        this.ttl = Duration.ofMinutes(minutes);
    }

    public String put(MoodAnalysis analysis, boolean fallback) {
        evictExpired();
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Entry(analysis, fallback, Instant.now()));
        return id;
    }

    public Optional<Entry> get(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) {
            return Optional.empty();
        }
        if (isExpired(entry)) {
            sessions.remove(sessionId);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public int size() {
        return sessions.size();
    }

    private void evictExpired() {
        sessions.entrySet().removeIf(e -> isExpired(e.getValue()));
    }

    private boolean isExpired(Entry entry) {
        return entry.createdAt().plus(ttl).isBefore(Instant.now());
    }
}
