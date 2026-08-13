package com.example.back.domain;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * ULID 생성기. 외부 의존성 없이 26자 Crockford Base32 문자열을 만든다.
 * <p>
 * 프로토타입의 {@code UUID.randomUUID()} 대신 쓰는 이유는 앞 10자가 밀리초
 * 타임스탬프라 문자열 정렬이 곧 시간순 정렬이기 때문이다. 이벤트 테이블을
 * 세션 기준으로 훑을 때 인덱스가 잘 먹고 로그 추적이 쉽다.
 * <p>
 * UUID 로 되돌리려면 {@link #generate(Instant)} 한 줄만 바꾸면 된다.
 * 다만 직원 BE 와의 연동 계약서에 ULID 26자로 명시되어 있으니 함께 고쳐야 한다.
 */
public final class SessionId {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionId() {
    }

    public static String generate(Instant now) {
        byte[] bytes = new byte[10];
        RANDOM.nextBytes(bytes);
        return encodeTime(now.toEpochMilli()) + encodeRandom(bytes);
    }

    /** 48비트 타임스탬프 → 10자. */
    private static String encodeTime(long millis) {
        char[] out = new char[10];
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (millis & 0x1F)];
            millis >>>= 5;
        }
        return new String(out);
    }

    /** 80비트 랜덤 → 16자. */
    private static String encodeRandom(byte[] bytes) {
        StringBuilder out = new StringBuilder(16);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(ALPHABET[(buffer >>> bits) & 0x1F]);
            }
        }
        return out.toString();
    }
}
