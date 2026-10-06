package com.gyejoldanji.domain.image.service;

import com.gyejoldanji.domain.image.client.ImageStorageClient;
import com.gyejoldanji.domain.image.client.model.PresignedUrl;
import com.gyejoldanji.domain.image.client.model.StoredObject;
import com.gyejoldanji.domain.image.dto.ImagePresignedUrlRequest;
import com.gyejoldanji.domain.image.dto.ImagePresignedUrlResponse;
import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 회원 소유 이미지 객체의 업로드 URL 발급·업로드 검증·커밋 후 정리·회원 폴더 삭제를 담당한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageStorageService {

    /** 기록 이미지 객체 키의 최상위 폴더. */
    public static final String RECORD_IMAGE_ROOT = "record-images/";
    /** 업로드 허용 크기(10MB). */
    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    /** 허용 Content-Type과 저장 확장자. */
    public static final Map<String, String> ALLOWED_CONTENT_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png");

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ImageStorageClient imageStorageClient;
    private final Clock clock;

    /** 회원 이미지 폴더 prefix({@code record-images/{memberId}/}). */
    public static String memberPrefix(Long memberId) {
        return RECORD_IMAGE_ROOT + memberId + "/";
    }

    /**
     * 현재 회원 폴더 아래 새 객체 키({@code record-images/{memberId}/{yyyy}/{MM}/{uuid}.{jpg|png}}, 한국 날짜 기준)로
     * 형식·크기가 서명된 5분짜리 업로드 URL을 발급한다.
     *
     * @throws BusinessException UNAUTHORIZED, INVALID_INPUT, IMAGE_INVALID_FORMAT, IMAGE_SIZE_EXCEEDED,
     *                           IMAGE_STORAGE_UNAVAILABLE
     */
    public ImagePresignedUrlResponse issueUploadUrl(Long memberId, ImagePresignedUrlRequest request) {
        if (memberId == null || memberId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (request == null || request.fileSize() == null || request.fileSize() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (!isAllowedContentType(request.contentType())) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID_FORMAT);
        }
        if (request.fileSize() > MAX_IMAGE_BYTES) {
            throw new BusinessException(ErrorCode.IMAGE_SIZE_EXCEEDED);
        }
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        String objectKey = "%s%04d/%02d/%s.%s".formatted(memberPrefix(memberId), today.getYear(),
                today.getMonthValue(), UUID.randomUUID(), ALLOWED_CONTENT_TYPES.get(request.contentType()));
        PresignedUrl uploadUrl = imageStorageClient.createUploadUrl(
                objectKey, request.contentType(), request.fileSize());
        return new ImagePresignedUrlResponse(objectKey, uploadUrl.url(), uploadUrl.expiresAt().toString(),
                uploadUrl.requiredHeaders());
    }

    /**
     * 기록에 연결할 객체들이 현재 회원 폴더에 실제로 업로드됐고 허용 형식·크기인지 검증한다.
     * 저장소 호출 전에 모든 키의 소유권을 먼저 확인한다.
     *
     * @throws BusinessException IMAGE_OWNERSHIP_MISMATCH, IMAGE_NOT_UPLOADED, IMAGE_INVALID_FORMAT,
     *                           IMAGE_SIZE_EXCEEDED, IMAGE_STORAGE_UNAVAILABLE
     */
    public void validateUploadedObjects(Long memberId, List<String> objectKeys) {
        String prefix = memberPrefix(memberId);
        for (String objectKey : objectKeys) {
            if (!objectKey.startsWith(prefix) || objectKey.length() == prefix.length()) {
                throw new BusinessException(ErrorCode.IMAGE_OWNERSHIP_MISMATCH);
            }
        }
        for (String objectKey : objectKeys) {
            StoredObject stored = imageStorageClient.head(objectKey)
                    .orElseThrow(() -> new BusinessException(ErrorCode.IMAGE_NOT_UPLOADED));
            if (!isAllowedContentType(stored.contentType())) {
                throw new BusinessException(ErrorCode.IMAGE_INVALID_FORMAT);
            }
            if (stored.contentLength() > MAX_IMAGE_BYTES) {
                throw new BusinessException(ErrorCode.IMAGE_SIZE_EXCEEDED);
            }
        }
    }

    /**
     * 비공개 객체를 한 시간 동안 조회할 수 있는 서명 URL을 발급한다.
     *
     * @throws BusinessException IMAGE_STORAGE_UNAVAILABLE
     */
    public String issueViewUrl(String objectKey) {
        return imageStorageClient.createViewUrl(objectKey).url();
    }

    /**
     * 삭제된 이미지의 원본·썸네일 객체를 현재 트랜잭션 커밋 뒤에 삭제하도록 예약한다. 롤백되면 아무것도 지우지 않는다.
     * 저장소 삭제 실패는 API 결과에 영향을 주지 않도록 로그만 남긴다.
     *
     * @throws IllegalStateException 활성 트랜잭션 밖에서 호출한 경우
     */
    public void scheduleDeletionAfterCommit(List<Image> images) {
        List<String> objectKeys = objectKeys(images);
        if (objectKeys.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("이미지 객체 삭제 예약은 트랜잭션 안에서만 할 수 있습니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    imageStorageClient.deleteObjects(objectKeys);
                } catch (RuntimeException exception) {
                    log.warn("커밋 후 이미지 객체 {}개 삭제 실패: {}",
                            objectKeys.size(), exception.getClass().getSimpleName());
                }
            }
        });
    }

    /**
     * 회원 이미지 폴더 전체를 삭제한다. 회원 데이터 삭제가 커밋된 뒤에 호출한다.
     *
     * @throws BusinessException IMAGE_STORAGE_UNAVAILABLE
     */
    public void deleteAllByMember(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("memberId는 양수여야 합니다.");
        }
        imageStorageClient.deleteAllByPrefix(memberPrefix(memberId));
    }

    /** 허용 Content-Type인지 확인한다. 파라미터(charset 등)는 허용하지 않는다. */
    public static boolean isAllowedContentType(String contentType) {
        return contentType != null && ALLOWED_CONTENT_TYPES.containsKey(contentType);
    }

    /** 원본 키와 null이 아닌 썸네일 키를 중복 없이 모은다. */
    private static List<String> objectKeys(List<Image> images) {
        Set<String> keys = new LinkedHashSet<>();
        for (Image image : images) {
            keys.add(image.getOriginalKey());
            if (image.getThumbnailKey() != null) {
                keys.add(image.getThumbnailKey());
            }
        }
        return new ArrayList<>(keys);
    }
}
