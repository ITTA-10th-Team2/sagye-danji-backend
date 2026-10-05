package com.gyejoldanji.domain.image.client.model;

import java.time.Instant;
import java.util.Map;

/**
 * 서명된 저장소 URL.
 *
 * @param url             서명된 URL
 * @param expiresAt       만료 시각
 * @param requiredHeaders 요청 시 그대로 보내야 하는 서명 헤더(Host 제외)
 */
public record PresignedUrl(String url, Instant expiresAt, Map<String, String> requiredHeaders) {

    public PresignedUrl {
        requiredHeaders = Map.copyOf(requiredHeaders);
    }
}
