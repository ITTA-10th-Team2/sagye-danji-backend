package com.gyejoldanji.domain.analytics.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 회원 번호를 시트 전용 가명으로 바꾼다. 같은 키·회원이면 같은 값이며 비밀 키를 바꾸면 모든 user_key가 바뀐다.
 * 작은 정수인 회원 번호를 대입해 역추적할 수 없도록 단순 해시 대신 비밀 키를 가진 HMAC을 쓴다.
 */
public final class AnalyticsUserKey {

    private AnalyticsUserKey() {
    }

    public static String generate(String secret, Long memberId) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(memberId, "memberId");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(("member:" + memberId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("분석 user_key 생성 실패");
        }
    }
}
