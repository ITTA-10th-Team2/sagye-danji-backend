package com.gyejoldanji.domain.record.service;

import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.image.repository.ImageRepository;
import com.gyejoldanji.domain.image.service.ImageStorageService;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.domain.jar.service.JarPageAllocationService;
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

    private static final int MAX_MEMO_CODE_POINTS = 100;
    private static final int MAX_OBJECT_KEY_LENGTH = 512;
    private static final String ORIGINAL_KEY_CONSTRAINT = "uk_images_original_key";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RecordRepository recordRepository;
    private final ImageRepository imageRepository;
    private final MemberRepository memberRepository;
    private final JarPageAllocationService jarPageAllocationService;
    private final ImageStorageService imageStorageService;
    private final SeasonResolver seasonResolver;
    private final Clock clock;

    /** 인증된 회원의 기록과 단일 이미지 메타데이터를 생성한다. */
    @Transactional
    public RecordResponse create(Long memberId, RecordCreateRequest request) {
        validateMemberId(memberId);
        validateMemo(request.memo());
        validateObjectKey(request.objectKey());
        rejectAttachedKey(request.objectKey());

        imageStorageService.validateUploadedObjects(memberId, List.of(request.objectKey()));
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_INVALID));
        LocalDate recordDate = request.recordDate() == null
                ? LocalDate.now(clock.withZone(SEOUL))
                : request.recordDate();

        try {
            var season = seasonResolver.resolve(recordDate);
            JarPage jarPage = jarPageAllocationService.allocate(member, recordDate.getYear(), season);
            Record record = recordRepository.save(Record.create(
                    member, jarPage, recordDate, season, request.memo()));
            Image image = imageRepository.save(Image.create(record, request.objectKey(), null));
            imageRepository.flush();
            recordRepository.flush();
            return RecordResponse.from(record, image, imageStorageService::issueViewUrl);
        } catch (DataIntegrityViolationException exception) {
            throw translateIntegrityViolation(exception);
        }
    }

    /** 소유한 기록의 날짜·메모와 선택적으로 전달된 단일 이미지를 함께 수정한다. */
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
        var nextSeason = seasonResolver.resolve(request.recordDate());
        boolean pageScopeChanged = record.getRecordDate().getYear() != request.recordDate().getYear()
                || record.getSeason() != nextSeason;
        record.update(request.recordDate(), nextSeason, request.memo());
        if (pageScopeChanged) {
            Member member = memberRepository.findByIdForUpdate(memberId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_SESSION_INVALID));
            record.moveToJarPage(jarPageAllocationService.allocate(
                    member, request.recordDate().getYear(), nextSeason));
        }

        ensureExactlyOneImage(currentImages);
        if (!StringUtils.hasText(request.objectKey())) {
            recordRepository.flush();
            return RecordResponse.from(record, currentImages, imageStorageService::issueViewUrl);
        }

        validateObjectKey(request.objectKey());
        rejectAttachedKey(request.objectKey());
        imageStorageService.validateUploadedObjects(memberId, List.of(request.objectKey()));

        try {
            Image removed = currentImages.getFirst();
            imageRepository.delete(removed);
            imageRepository.flush();
            Image added = imageRepository.save(Image.create(record, request.objectKey(), null));
            imageRepository.flush();
            recordRepository.flush();
            imageStorageService.scheduleDeletionAfterCommit(List.of(removed));
            return RecordResponse.from(record, added, imageStorageService::issueViewUrl);
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

    /** 기존 데이터도 단일 이미지 불변식을 만족하는지 검증한다. */
    private void ensureExactlyOneImage(List<Image> images) {
        if (images.size() != 1) {
            throw new BusinessException(ErrorCode.RECORD_IMAGE_INTEGRITY_VIOLATION);
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
    private void rejectAttachedKey(String objectKey) {
        if (imageRepository.existsByOriginalKeyIn(List.of(objectKey))) {
            throw new BusinessException(ErrorCode.IMAGE_ALREADY_ATTACHED);
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
