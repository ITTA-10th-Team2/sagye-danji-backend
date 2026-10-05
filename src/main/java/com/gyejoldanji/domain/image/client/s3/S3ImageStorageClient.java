package com.gyejoldanji.domain.image.client.s3;

import com.gyejoldanji.domain.image.client.ImageStorageClient;
import com.gyejoldanji.domain.image.client.model.PresignedUrl;
import com.gyejoldanji.domain.image.client.model.StoredObject;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import com.gyejoldanji.global.infrastructure.s3.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.presigner.PresignedRequest;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** AWS SDK v2로 S3 객체를 조회·삭제하고 Presigned URL을 서명한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.s3", name = "enabled", havingValue = "true")
public class S3ImageStorageClient implements ImageStorageClient {

    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(5);
    private static final Duration VIEW_URL_TTL = Duration.ofHours(1);
    /** DeleteObjects 한 번에 보낼 수 있는 최대 키 수. */
    private static final int DELETE_BATCH_SIZE = 1000;

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties properties;

    /** HeadObject로 객체 메타데이터를 조회하며 404는 빈 값으로 돌려준다. */
    @Override
    public Optional<StoredObject> head(String objectKey) {
        try {
            HeadObjectResponse response = s3Client.headObject(request -> request
                    .bucket(properties.getBucket())
                    .key(objectKey));
            return Optional.of(new StoredObject(response.contentType(), response.contentLength()));
        } catch (NoSuchKeyException exception) {
            return Optional.empty();
        } catch (S3Exception exception) {
            // HeadObject는 본문이 없어 없는 객체도 NoSuchKey가 아닌 일반 404로 올 수 있다.
            if (exception.statusCode() == 404) {
                return Optional.empty();
            }
            throw unavailable("head", exception);
        } catch (SdkException exception) {
            throw unavailable("head", exception);
        }
    }

    /** DeleteObjects로 1000개씩 나눠 삭제하고 개별 실패가 있으면 저장소 오류로 처리한다. */
    @Override
    public void deleteObjects(List<String> objectKeys) {
        if (objectKeys.isEmpty()) {
            return;
        }
        try {
            for (int from = 0; from < objectKeys.size(); from += DELETE_BATCH_SIZE) {
                List<ObjectIdentifier> identifiers = objectKeys
                        .subList(from, Math.min(from + DELETE_BATCH_SIZE, objectKeys.size())).stream()
                        .map(key -> ObjectIdentifier.builder().key(key).build())
                        .toList();
                DeleteObjectsResponse response = s3Client.deleteObjects(request -> request
                        .bucket(properties.getBucket())
                        .delete(Delete.builder().objects(identifiers).quiet(true).build()));
                if (response.hasErrors() && !response.errors().isEmpty()) {
                    throw new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE,
                            "이미지 객체 " + response.errors().size() + "개를 삭제하지 못했습니다.");
                }
            }
        } catch (SdkException exception) {
            throw unavailable("deleteObjects", exception);
        }
    }

    /** prefix 아래 키를 모두 나열한 뒤 일괄 삭제한다. 버킷 전체 삭제를 막기 위해 '/'로 끝나는 prefix만 받는다. */
    @Override
    public void deleteAllByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank() || !prefix.endsWith("/")) {
            throw new IllegalArgumentException("삭제 prefix는 '/'로 끝나는 비어 있지 않은 값이어야 합니다.");
        }
        List<String> keys;
        try {
            keys = s3Client.listObjectsV2Paginator(request -> request
                            .bucket(properties.getBucket())
                            .prefix(prefix))
                    .contents().stream()
                    .map(S3Object::key)
                    .toList();
        } catch (SdkException exception) {
            throw unavailable("listObjectsV2", exception);
        }
        deleteObjects(keys);
    }

    /** Content-Type과 Content-Length를 서명에 포함해 다른 형식·크기의 업로드를 S3가 거부하게 한다. */
    @Override
    public PresignedUrl createUploadUrl(String objectKey, String contentType, long contentLength) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        try {
            return toPresignedUrl(s3Presigner.presignPutObject(request -> request
                    .signatureDuration(UPLOAD_URL_TTL)
                    .putObjectRequest(putObjectRequest)));
        } catch (SdkException exception) {
            throw unavailable("presignPutObject", exception);
        }
    }

    /** 조회용 GET URL을 서명한다. */
    @Override
    public PresignedUrl createViewUrl(String objectKey) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(objectKey)
                .build();
        try {
            return toPresignedUrl(s3Presigner.presignGetObject(request -> request
                    .signatureDuration(VIEW_URL_TTL)
                    .getObjectRequest(getObjectRequest)));
        } catch (SdkException exception) {
            throw unavailable("presignGetObject", exception);
        }
    }

    /** SDK 서명 결과를 도메인 모델로 바꾸며 브라우저가 자동으로 붙이는 Host 헤더는 제외한다. */
    private static PresignedUrl toPresignedUrl(PresignedRequest presigned) {
        SdkHttpRequest httpRequest = presigned.httpRequest();
        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!"host".equalsIgnoreCase(name)) {
                headers.put(name, String.join(",", values));
            }
        });
        return new PresignedUrl(httpRequest.getUri().toString(), presigned.expiration(), headers);
    }

    /** SDK 오류를 저장소 접근 실패로 변환한다. 객체 키는 로그에 남기지 않는다. */
    private static BusinessException unavailable(String operation, SdkException exception) {
        log.warn("S3 {} 실패: {}", operation, exception.getClass().getSimpleName());
        return new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE, exception);
    }
}
