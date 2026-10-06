package com.gyejoldanji.domain.record.service;

import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.image.repository.ImageRepository;
import com.gyejoldanji.domain.image.service.ImageStorageService;
import com.gyejoldanji.domain.record.dto.RecordCursorPageResponse;
import com.gyejoldanji.domain.record.dto.RecordListItemResponse;
import com.gyejoldanji.domain.record.dto.RecordResponse;
import com.gyejoldanji.domain.record.dto.RecordSummaryResponse;
import com.gyejoldanji.domain.record.dto.SeasonRecordCursorPageResponse;
import com.gyejoldanji.domain.record.dto.SeasonRecordListItemResponse;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.domain.record.repository.RecordRepository;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 현재 회원의 전체·계절별 기록 목록과 상세를 읽기 전용 트랜잭션으로 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecordQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 50;

    private final RecordRepository recordRepository;
    private final ImageRepository imageRepository;
    private final ImageStorageService imageStorageService;
    private final RecordCursorCodec cursorCodec;
    private final Clock clock;

    /** 회원의 전체 기록 수와 첫 기록일부터 오늘까지의 누적 일수를 조회한다. */
    public RecordSummaryResponse findSummary(Long memberId) {
        validateMemberId(memberId);
        RecordRepository.RecordSummaryProjection summary = recordRepository.summarizeByMemberId(memberId);
        if (summary.getRecordCount() == 0 || summary.getFirstRecordDate() == null) {
            return new RecordSummaryResponse(0, 0);
        }
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        long recordingDayCount = ChronoUnit.DAYS.between(summary.getFirstRecordDate(), today) + 1;
        return new RecordSummaryResponse(summary.getRecordCount(), recordingDayCount);
    }

    /** 회원의 특정 연도 전체 기록을 최신순 cursor 페이지로 조회한다. */
    public RecordCursorPageResponse findAll(Long memberId, int year, String encodedCursor, int size) {
        validateMemberId(memberId);
        validateYear(year);
        validatePageSize(size);
        DateRange range = DateRange.of(year);
        RecordCursorCodec.Cursor cursor = cursorCodec.decodeNullable(encodedCursor);
        List<Record> records = cursor == null
                ? recordRepository.findOwnedInDateRange(memberId, range.start(), range.endExclusive(),
                        PageRequest.of(0, size + 1))
                : recordRepository.findOwnedInDateRangeAfter(memberId, range.start(), range.endExclusive(),
                        cursor.recordDate(), cursor.recordId(), PageRequest.of(0, size + 1));
        return toPage(records, size);
    }

    /** 회원의 특정 연도·계절 기록을 최신순 cursor 페이지로 조회한다. */
    public SeasonRecordCursorPageResponse findBySeason(Long memberId, int year, SeasonType season,
                                                        String encodedCursor, int size) {
        validateMemberId(memberId);
        validateYear(year);
        validatePageSize(size);
        if (season == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        DateRange range = DateRange.of(year);
        RecordCursorCodec.Cursor cursor = cursorCodec.decodeNullable(encodedCursor);
        List<Record> records = cursor == null
                ? recordRepository.findOwnedBySeasonInDateRange(memberId, season, range.start(), range.endExclusive(),
                        PageRequest.of(0, size + 1))
                : recordRepository.findOwnedBySeasonInDateRangeAfter(memberId, season, range.start(),
                        range.endExclusive(), cursor.recordDate(), cursor.recordId(),
                        PageRequest.of(0, size + 1));
        return toSeasonPage(records, size);
    }

    /** ID와 회원 ID를 함께 조건화해 소유한 기록의 상세와 정렬된 이미지를 조회한다. */
    public RecordResponse findDetail(Long memberId, Long recordId) {
        validateMemberId(memberId);
        if (recordId == null || recordId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Record record = recordRepository.findOwnedById(recordId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECORD_NOT_FOUND));
        List<Image> images = imageRepository.findAllByRecordIdOrderBySortOrderAsc(recordId);
        return RecordResponse.from(record, images, imageStorageService::issueViewUrl);
    }

    /** limit+1 조회 결과를 N+1 없는 목록 응답과 다음 cursor로 변환한다. */
    private RecordCursorPageResponse toPage(List<Record> queriedRecords, int limit) {
        PageSource source = loadPageSource(queriedRecords, limit);
        List<RecordListItemResponse> items = source.records().stream()
                .map(record -> RecordListItemResponse.from(record,
                        source.imagesByRecordId().getOrDefault(record.getId(), List.of()),
                        imageStorageService::issueViewUrl))
                .toList();
        return new RecordCursorPageResponse(items, source.nextCursor(), source.hasNext());
    }

    /** 계절 단지 목록에 기록별 이미지 미리보기를 최대 두 장 포함한다. */
    private SeasonRecordCursorPageResponse toSeasonPage(List<Record> queriedRecords, int limit) {
        PageSource source = loadPageSource(queriedRecords, limit);
        List<SeasonRecordListItemResponse> items = source.records().stream()
                .map(record -> SeasonRecordListItemResponse.from(record,
                        source.imagesByRecordId().getOrDefault(record.getId(), List.of()),
                        imageStorageService::issueViewUrl))
                .toList();
        return new SeasonRecordCursorPageResponse(items, source.nextCursor(), source.hasNext());
    }

    /** 페이지의 기록과 이미지를 각각 한 번에 조회해 목록 종류가 늘어도 DB N+1을 방지한다. */
    private PageSource loadPageSource(List<Record> queriedRecords, int limit) {
        boolean hasNext = queriedRecords.size() > limit;
        List<Record> records = hasNext ? queriedRecords.subList(0, limit) : queriedRecords;
        List<Long> recordIds = records.stream().map(Record::getId).toList();
        Map<Long, List<Image>> imagesByRecordId = recordIds.isEmpty()
                ? Collections.emptyMap()
                : imageRepository.findAllByRecordIds(recordIds).stream()
                        .collect(Collectors.groupingBy(image -> image.getRecord().getId()));
        String nextCursor = hasNext
                ? cursorCodec.encode(records.getLast().getRecordDate(), records.getLast().getId())
                : null;
        return new PageSource(records, imagesByRecordId, nextCursor, hasNext);
    }

    /** 인증 계층에서 전달된 회원 ID가 서비스 내부 호출에서도 유효한지 방어한다. */
    private static void validateMemberId(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_INVALID);
        }
    }

    /** LocalDate 생성 전에 지원하는 달력 연도 범위를 검증한다. */
    private static void validateYear(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    /** 목록 종류와 무관하게 클라이언트 페이지 크기를 동일 범위로 제한한다. */
    private static void validatePageSize(int size) {
        if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    /** 인덱스를 사용할 수 있도록 함수 대신 반개구 연도 범위를 제공한다. */
    private record DateRange(LocalDate start, LocalDate endExclusive) {
        private static DateRange of(int year) {
            LocalDate start = LocalDate.of(year, 1, 1);
            return new DateRange(start, start.plusYears(1));
        }
    }

    /** 목록 응답 종류가 공유하는 페이지 절단·이미지 일괄 조회 결과. */
    private record PageSource(List<Record> records, Map<Long, List<Image>> imagesByRecordId,
                              String nextCursor, boolean hasNext) {
    }
}
