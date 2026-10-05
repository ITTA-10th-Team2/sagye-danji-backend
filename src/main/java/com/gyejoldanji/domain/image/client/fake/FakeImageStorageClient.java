package com.gyejoldanji.domain.image.client.fake;

import com.gyejoldanji.domain.image.client.ImageStorageClient;
import com.gyejoldanji.domain.image.client.model.PresignedUrl;
import com.gyejoldanji.domain.image.client.model.StoredObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS 없이 로컬·CI에서 쓰는 메모리 기반 저장소({@code app.s3.enabled=false}).
 *
 * <p>업로드 URL을 발급하면 해당 객체가 선언한 형식·크기로 업로드된 것으로 간주해, 로컬에서도 발급 → 기록 생성 흐름이 동작한다.
 */
@Component
@ConditionalOnProperty(prefix = "app.s3", name = "enabled", havingValue = "false", matchIfMissing = true)
public class FakeImageStorageClient implements ImageStorageClient {

    private static final String BASE_URL = "https://fake-image-storage.local/";
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(5);
    private static final Duration VIEW_URL_TTL = Duration.ofHours(1);

    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private final Clock clock;

    public FakeImageStorageClient(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<StoredObject> head(String objectKey) {
        return Optional.ofNullable(objects.get(objectKey));
    }

    @Override
    public void deleteObjects(List<String> objectKeys) {
        objectKeys.forEach(objects::remove);
    }

    @Override
    public void deleteAllByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank() || !prefix.endsWith("/")) {
            throw new IllegalArgumentException("삭제 prefix는 '/'로 끝나는 비어 있지 않은 값이어야 합니다.");
        }
        objects.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @Override
    public PresignedUrl createUploadUrl(String objectKey, String contentType, long contentLength) {
        objects.put(objectKey, new StoredObject(contentType, contentLength));
        return new PresignedUrl(url(objectKey, "PUT"), clock.instant().plus(UPLOAD_URL_TTL), Map.of(
                "content-type", contentType,
                "content-length", Long.toString(contentLength)));
    }

    @Override
    public PresignedUrl createViewUrl(String objectKey) {
        return new PresignedUrl(url(objectKey, "GET"), clock.instant().plus(VIEW_URL_TTL), Map.of());
    }

    /** 테스트에서 임의의 업로드 결과를 직접 넣는다. */
    public void put(String objectKey, String contentType, long contentLength) {
        objects.put(objectKey, new StoredObject(contentType, contentLength));
    }

    /** 테스트에서 객체 존재 여부를 확인한다. */
    public boolean contains(String objectKey) {
        return objects.containsKey(objectKey);
    }

    private static String url(String objectKey, String method) {
        return BASE_URL + URLEncoder.encode(objectKey, StandardCharsets.UTF_8) + "?method=" + method;
    }
}
