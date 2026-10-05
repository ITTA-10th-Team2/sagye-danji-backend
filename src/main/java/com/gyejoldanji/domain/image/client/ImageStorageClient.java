package com.gyejoldanji.domain.image.client;

import com.gyejoldanji.domain.image.client.model.PresignedUrl;
import com.gyejoldanji.domain.image.client.model.StoredObject;

import java.util.List;
import java.util.Optional;

/**
 * 이미지 서비스가 사용하는 객체 저장소 접근 계약.
 *
 * <p>구현체는 저장소 접근 실패를 {@code BusinessException(IMAGE_STORAGE_UNAVAILABLE)}로 변환한다.
 */
public interface ImageStorageClient {

    /** 객체의 존재 여부와 Content-Type·크기를 조회한다. 객체가 없으면 빈 값이다. */
    Optional<StoredObject> head(String objectKey);

    /** 주어진 객체들을 삭제한다. 이미 없는 객체는 성공으로 본다. */
    void deleteObjects(List<String> objectKeys);

    /** prefix 아래의 모든 객체를 삭제한다. */
    void deleteAllByPrefix(String prefix);

    /** Content-Type과 크기가 서명된 5분짜리 업로드(PUT) URL을 발급한다. */
    PresignedUrl createUploadUrl(String objectKey, String contentType, long contentLength);

    /** 1시간짜리 조회(GET) URL을 발급한다. */
    PresignedUrl createViewUrl(String objectKey);
}
