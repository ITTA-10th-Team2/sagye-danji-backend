package com.gyejoldanji.domain.image.service;

import com.gyejoldanji.domain.image.client.fake.FakeImageStorageClient;
import com.gyejoldanji.domain.image.dto.ImagePresignedUrlRequest;
import com.gyejoldanji.domain.image.dto.ImagePresignedUrlResponse;
import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.image.enums.PhotoSource;
import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fake 저장소로 업로드 검증·URL 발급·커밋 후 삭제·회원 폴더 삭제를 검증한다. */
class ImageStorageServiceTest {

    private static final long MAX = ImageStorageService.MAX_IMAGE_BYTES;

    private FakeImageStorageClient storage;
    private ImageStorageService service;

    @BeforeEach
    void setUp() {
        // 한국 날짜로는 2026-11-01, UTC로는 2026-10-31이다.
        Clock clock = Clock.fixed(Instant.parse("2026-10-31T16:30:00Z"), ZoneOffset.UTC);
        storage = new FakeImageStorageClient(clock);
        service = new ImageStorageService(storage, clock);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void acceptsUploadedJpegAndPngWithinLimit() {
        storage.put("record-images/42/2026/10/a.jpg", "image/jpeg", MAX);
        storage.put("record-images/42/2026/10/b.png", "image/png", 1);

        assertThatCode(() -> service.validateUploadedObjects(42L,
                List.of("record-images/42/2026/10/a.jpg", "record-images/42/2026/10/b.png")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"record-images/43/2026/10/a.jpg", "record-images/420/a.jpg", "record-images/42/",
            "other/42/a.jpg", "record-images/42"})
    void rejectsKeysOutsideCurrentMemberFolder(String objectKey) {
        storage.put(objectKey, "image/jpeg", 1);

        expectError(() -> service.validateUploadedObjects(42L, List.of(objectKey)),
                ErrorCode.IMAGE_OWNERSHIP_MISMATCH);
    }

    @Test
    void checksOwnershipOfEveryKeyBeforeStorageLookup() {
        // 첫 키가 업로드되지 않았어도 다른 회원 키가 섞이면 소유권 오류가 먼저다.
        expectError(() -> service.validateUploadedObjects(42L,
                        List.of("record-images/42/missing.jpg", "record-images/43/a.jpg")),
                ErrorCode.IMAGE_OWNERSHIP_MISMATCH);
    }

    @Test
    void rejectsMissingObject() {
        expectError(() -> service.validateUploadedObjects(42L, List.of("record-images/42/missing.jpg")),
                ErrorCode.IMAGE_NOT_UPLOADED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/webp", "image/gif", "IMAGE/JPEG", "image/jpeg; charset=utf-8", "text/plain"})
    void rejectsUnsupportedContentType(String contentType) {
        storage.put("record-images/42/a.jpg", contentType, 1);

        expectError(() -> service.validateUploadedObjects(42L, List.of("record-images/42/a.jpg")),
                ErrorCode.IMAGE_INVALID_FORMAT);
    }

    @Test
    void rejectsObjectWithoutContentType() {
        storage.put("record-images/42/a.jpg", null, 1);

        expectError(() -> service.validateUploadedObjects(42L, List.of("record-images/42/a.jpg")),
                ErrorCode.IMAGE_INVALID_FORMAT);
    }

    @Test
    void rejectsObjectLargerThanTenMegabytes() {
        storage.put("record-images/42/a.png", "image/png", MAX + 1);

        expectError(() -> service.validateUploadedObjects(42L, List.of("record-images/42/a.png")),
                ErrorCode.IMAGE_SIZE_EXCEEDED);
    }

    @Test
    void issuesUploadUrlUnderMemberFolderWithKoreanDate() {
        ImagePresignedUrlResponse response =
                service.issueUploadUrl(42L, new ImagePresignedUrlRequest("image/png", 2048L));

        assertThat(response.objectKey()).matches("record-images/42/2026/11/[0-9a-f-]{36}\\.png");
        assertThat(response.expiresAt()).isEqualTo("2026-10-31T16:35:00Z");
        assertThat(response.requiredHeaders())
                .containsEntry("content-type", "image/png")
                .containsEntry("content-length", "2048");
        // 발급한 키는 그대로 기록 API 검증을 통과한다.
        assertThatCode(() -> service.validateUploadedObjects(42L, List.of(response.objectKey())))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUploadUrlForUnsupportedTypeOrSize() {
        expectError(() -> service.issueUploadUrl(42L, new ImagePresignedUrlRequest("image/webp", 1L)),
                ErrorCode.IMAGE_INVALID_FORMAT);
        expectError(() -> service.issueUploadUrl(42L, new ImagePresignedUrlRequest("image/jpeg", MAX + 1)),
                ErrorCode.IMAGE_SIZE_EXCEEDED);
        expectError(() -> service.issueUploadUrl(42L, new ImagePresignedUrlRequest("image/jpeg", 0L)),
                ErrorCode.INVALID_INPUT);
    }

    @Test
    void deletesOriginalAndThumbnailOnlyAfterCommit() {
        storage.put("record-images/42/a.jpg", "image/jpeg", 1);
        storage.put("record-images/42/a-thumb.jpg", "image/jpeg", 1);
        storage.put("record-images/42/b.jpg", "image/jpeg", 1);
        TransactionSynchronizationManager.initSynchronization();

        service.scheduleDeletionAfterCommit(List.of(
                image("record-images/42/a.jpg", "record-images/42/a-thumb.jpg"),
                image("record-images/42/b.jpg", null)));

        assertThat(storage.contains("record-images/42/a.jpg")).as("커밋 전에는 지우지 않는다").isTrue();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(storage.contains("record-images/42/a.jpg")).isFalse();
        assertThat(storage.contains("record-images/42/a-thumb.jpg")).isFalse();
        assertThat(storage.contains("record-images/42/b.jpg")).isFalse();
    }

    @Test
    void swallowsStorageFailureAfterCommit() {
        ImageStorageService failing = new ImageStorageService(new FakeImageStorageClient(Clock.systemUTC()) {
            @Override
            public void deleteObjects(List<String> objectKeys) {
                throw new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE);
            }
        }, Clock.systemUTC());
        TransactionSynchronizationManager.initSynchronization();
        failing.scheduleDeletionAfterCommit(List.of(image("record-images/42/a.jpg", null)));

        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
    }

    @Test
    void refusesToScheduleOutsideTransaction() {
        assertThatThrownBy(() -> service.scheduleDeletionAfterCommit(List.of(image("record-images/42/a.jpg", null))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deletesOnlyCurrentMemberFolder() {
        storage.put("record-images/42/2026/10/a.jpg", "image/jpeg", 1);
        storage.put("record-images/420/2026/10/a.jpg", "image/jpeg", 1);
        storage.put("record-images/43/2026/10/a.jpg", "image/jpeg", 1);

        service.deleteAllByMember(42L);

        assertThat(storage.contains("record-images/42/2026/10/a.jpg")).isFalse();
        assertThat(storage.contains("record-images/420/2026/10/a.jpg")).isTrue();
        assertThat(storage.contains("record-images/43/2026/10/a.jpg")).isTrue();
    }

    private static void expectError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static Image image(String originalKey, String thumbnailKey) {
        Member member = Member.create("TOSS_ANON", "member-42");
        ReflectionTestUtils.setField(member, "id", 42L);
        Record record = Record.create(member, LocalDate.of(2026, 10, 4), SeasonType.AUTUMN, null);
        return Image.create(record, originalKey, thumbnailKey, PhotoSource.CAMERA, 0);
    }
}
