package com.gyejoldanji.domain.record.service;

import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.image.repository.ImageRepository;
import com.gyejoldanji.domain.image.service.ImageStorageService;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.member.repository.MemberRepository;
import com.gyejoldanji.domain.record.dto.RecordCreateRequest;
import com.gyejoldanji.domain.record.dto.RecordResponse;
import com.gyejoldanji.domain.record.dto.RecordUpdateRequest;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.domain.record.repository.RecordRepository;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 현재 회원의 기록과 이미지 메타데이터를 하나의 DB 트랜잭션으로 생성·수정·삭제한다. */
@Service
@RequiredArgsConstructor
public class RecordCommandService {

    private static final int MAX_IMAGE_COUNT = 10;
    private static final int MAX_MEMO_CODE_POINTS = 100;
    private static final int MAX_OBJECT_KEY_LENGTH = 512;
    private static final String ORIGINAL_KEY_CONSTRAINT = "uk_images_original_key";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RecordRepository recordRepository;
    private final ImageRepository imageRepository;
    private final MemberRepository memberRepository;
    private final ImageStorageService imageStorageService;
    private final SeasonResolver seasonResolver;
    private final Clock clock;

    /** 인증된 회원의 기록과 1~10개 이미지 메타데이터를 생성한다. */
    @Transactional
    public RecordResponse create(Long memberId, RecordCreateRequest request) {
        validateMemberId(memberId);
        validateMemo(request.memo());
        validateCreateImages(request.images());
        List<String> objectKeys = request.images().stream().map(RecordCreateRequest.ImageItem::objectKey).toList();
        rejectAttachedKeys(objectKeys);

        imageStorageService.validateUploadedObjects(memberId, objectKeys);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_INVALID));
        LocalDate recordDate = request.recordDate() == null
                ? LocalDate.now(clock.withZone(SEOUL))
                : request.recordDate();

        try {
            Record record = recordRepository.save(Record.create(
                    member, recordDate, seasonResolver.resolve(recordDate), request.memo()));
            List<Image> images = request.images().stream()
                    .map(item -> Image.create(record, item.objectKey(), null, item.source(), item.sortOrder()))
                    .toList();
            List<Image> savedImages = imageRepository.saveAll(images);
            imageRepository.flush();
            recordRepository.flush();
            return RecordResponse.from(record, savedImages, imageStorageService::issueViewUrl);
        } catch (DataIntegrityViolationException exception) {
            throw translateIntegrityViolation(exception);
        }
    }

    /** 소유한 기록의 날짜·메모와 선택적으로 전달된 최종 이미지 구성을 함께 수정한다. */
    @Transactional
    public RecordResponse update(Long memberId, Long recordId, RecordUpdateRequest request) {
        validateMemberId(memberId);
        validateRecordId(recordId);
        validateMemo(request.memo());
        if (request.recordDate() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        Record record = ownedRecordForUpdate(recordId, memberId);
        List<Image> currentImages = imageRepository.findAllByRecordIdForUpdate(recordId);
        record.update(request.recordDate(), seasonResolver.resolve(request.recordDate()), request.memo());

        if (request.images() == null) {
            recordRepository.flush();
            return RecordResponse.from(record, currentImages, imageStorageService::issueViewUrl);
        }

        validateUpdateImages(request.images());
        Map<Long, Image> currentById = new HashMap<>();
        currentImages.forEach(image -> currentById.put(image.getId(), image));

        Set<Long> retainedIds = new HashSet<>();
        List<Image> retained = new ArrayList<>();
        List<RecordUpdateRequest.ImageItem> newItems = new ArrayList<>();
        for (RecordUpdateRequest.ImageItem item : request.images()) {
            if (item.type() == RecordUpdateRequest.Type.EXISTING) {
                Image image = currentById.get(item.imageId());
                if (image == null || !retainedIds.add(item.imageId())) {
                    throw new BusinessException(ErrorCode.RECORD_NOT_FOUND);
                }
                retained.add(image);
            } else {
                newItems.add(item);
            }
        }

        List<String> newKeys = newItems.stream().map(RecordUpdateRequest.ImageItem::objectKey).toList();
        rejectAttachedKeys(newKeys);
        imageStorageService.validateUploadedObjects(memberId, newKeys);

        try {
            moveToTemporaryOrders(retained, currentImages);
            imageRepository.flush();

            List<Image> removed = currentImages.stream()
                    .filter(image -> !retainedIds.contains(image.getId()))
                    .toList();
            imageRepository.deleteAll(removed);
            imageRepository.flush();
            imageStorageService.scheduleDeletionAfterCommit(removed);

            Map<Long, Integer> finalExistingOrders = new HashMap<>();
            request.images().stream()
                    .filter(item -> item.type() == RecordUpdateRequest.Type.EXISTING)
                    .forEach(item -> finalExistingOrders.put(item.imageId(), item.sortOrder()));
            retained.forEach(image -> image.changeSortOrder(finalExistingOrders.get(image.getId())));

            List<Image> added = newItems.stream()
                    .map(item -> Image.create(record, item.objectKey(), null, item.source(), item.sortOrder()))
                    .toList();
            List<Image> savedAdded = imageRepository.saveAll(added);
            imageRepository.flush();
            recordRepository.flush();

            List<Image> finalImages = new ArrayList<>(retained);
            finalImages.addAll(savedAdded);
            return RecordResponse.from(record, finalImages, imageStorageService::issueViewUrl);
        } catch (DataIntegrityViolationException exception) {
            throw translateIntegrityViolation(exception);
        }
    }

    /** 소유한 기록과 연결된 이미지 메타데이터를 같은 트랜잭션에서 삭제한다. */
    @Transactional
    public void delete(Long memberId, Long recordId) {
        validateMemberId(memberId);
        validateRecordId(recordId);
        Record record = ownedRecordForUpdate(recordId, memberId);
        List<Image> images = imageRepository.findAllByRecordIdForUpdate(recordId);
        imageRepository.deleteAll(images);
        imageRepository.flush();
        recordRepository.delete(record);
        recordRepository.flush();
        imageStorageService.scheduleDeletionAfterCommit(images);
    }

    /** 회원 소유권을 조회 조건에 포함해 없는 기록과 다른 회원 기록을 같은 404로 처리한다. */
    private Record ownedRecordForUpdate(Long recordId, Long memberId) {
        return recordRepository.findOwnedByIdForUpdate(recordId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECORD_NOT_FOUND));
    }

    /** create 이미지 개수·키·순서와 요청 내 중복을 검증한다. */
    private void validateCreateImages(List<RecordCreateRequest.ImageItem> images) {
        validateImageCount(images);
        rejectNullImageItems(images);
        validateOrders(images.stream().map(RecordCreateRequest.ImageItem::sortOrder).toList(), images.size());
        Set<String> keys = new HashSet<>();
        for (RecordCreateRequest.ImageItem image : images) {
            validateObjectKey(image.objectKey());
            if (image.source() == null || !keys.add(image.objectKey())) {
                throw new BusinessException(image.source() == null
                        ? ErrorCode.INVALID_INPUT
                        : ErrorCode.IMAGE_ALREADY_ATTACHED);
            }
        }
    }

    /** update 이미지의 타입별 필드 조합, 개수, 순서와 요청 내 중복을 검증한다. */
    private void validateUpdateImages(List<RecordUpdateRequest.ImageItem> images) {
        validateImageCount(images);
        rejectNullImageItems(images);
        validateOrders(images.stream().map(RecordUpdateRequest.ImageItem::sortOrder).toList(), images.size());
        Set<Long> imageIds = new HashSet<>();
        Set<String> objectKeys = new HashSet<>();
        for (RecordUpdateRequest.ImageItem item : images) {
            if (item.type() == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            if (item.type() == RecordUpdateRequest.Type.EXISTING) {
                if (item.imageId() == null || item.imageId() <= 0 || item.objectKey() != null || item.source() != null
                        || !imageIds.add(item.imageId())) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT);
                }
                continue;
            }
            validateObjectKey(item.objectKey());
            if (item.imageId() != null || item.source() == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            if (!objectKeys.add(item.objectKey())) {
                throw new BusinessException(ErrorCode.IMAGE_ALREADY_ATTACHED);
            }
        }
    }

    /** final sortOrder가 정확히 0부터 n-1까지 한 번씩 존재하는지 검증한다. */
    private void validateOrders(List<Integer> orders, int size) {
        if (orders.stream().anyMatch(order -> order == null || order < 0)) {
            throw new BusinessException(ErrorCode.RECORD_IMAGE_ORDER_INVALID);
        }
        Set<Integer> unique = new HashSet<>(orders);
        if (unique.size() != size) {
            throw new BusinessException(ErrorCode.RECORD_IMAGE_ORDER_INVALID);
        }
        for (int order = 0; order < size; order++) {
            if (!unique.contains(order)) {
                throw new BusinessException(ErrorCode.RECORD_IMAGE_ORDER_INVALID);
            }
        }
    }

    /** 최종 이미지 수가 1~10개인지 검증한다. */
    private void validateImageCount(List<?> images) {
        if (images == null || images.isEmpty()) {
            throw new BusinessException(ErrorCode.RECORD_IMAGE_REQUIRED);
        }
        if (images.size() > MAX_IMAGE_COUNT) {
            throw new BusinessException(ErrorCode.RECORD_IMAGE_LIMIT_EXCEEDED);
        }
    }

    /** 목록 원소가 null이면 service 직접 호출에서도 NullPointerException 대신 입력 오류로 변환한다. */
    private void rejectNullImageItems(List<?> images) {
        if (images.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    /** object key의 DB 저장 범위만 검증한다. 소유권·존재·형식·크기는 {@link ImageStorageService#validateUploadedObjects}가 검증한다. */
    private void validateObjectKey(String objectKey) {
        if (!StringUtils.hasText(objectKey) || objectKey.length() > MAX_OBJECT_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    /** 입력 경계 밖의 직접 service 호출에서도 entity 예외가 500으로 새지 않게 memo 길이를 검증한다. */
    private void validateMemo(String memo) {
        if (memo != null && memo.codePointCount(0, memo.length()) > MAX_MEMO_CODE_POINTS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    /** 이미 어느 기록에 연결된 객체 키가 있으면 신규 연결을 거부한다. */
    private void rejectAttachedKeys(List<String> objectKeys) {
        if (!objectKeys.isEmpty() && imageRepository.existsByOriginalKeyIn(objectKeys)) {
            throw new BusinessException(ErrorCode.IMAGE_ALREADY_ATTACHED);
        }
    }

    /** 기존 순서와 충돌하지 않는 동적 임시 범위로 유지 이미지들을 이동한다. */
    private void moveToTemporaryOrders(List<Image> retained, List<Image> currentImages) {
        int maxOrder = currentImages.stream().map(Image::getSortOrder).max(Comparator.naturalOrder()).orElse(-1);
        long lastTemporary = (long) maxOrder + retained.size();
        if (lastTemporary > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        int temporary = maxOrder + 1;
        for (Image image : retained) {
            image.changeSortOrder(temporary++);
        }
    }

    /** ID는 인증·경로 경계뿐 아니라 service 직접 호출에서도 양수만 허용한다. */
    private void validateMemberId(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    /** 경로 record ID는 양수만 허용한다. */
    private void validateRecordId(Long recordId) {
        if (recordId == null || recordId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    /** original key unique 제약 경쟁만 특정 오류로 바꾸고 다른 무결성 오류는 기존 전역 처리에 맡긴다. */
    private RuntimeException translateIntegrityViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String constraintName = violation.getConstraintName();
                if (constraintName != null
                        && ORIGINAL_KEY_CONSTRAINT.equalsIgnoreCase(constraintName.replace("`", ""))) {
                    return new BusinessException(ErrorCode.IMAGE_ALREADY_ATTACHED, exception);
                }
            }
        }
        return exception;
    }
}
