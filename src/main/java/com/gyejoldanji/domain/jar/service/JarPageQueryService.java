package com.gyejoldanji.domain.jar.service;

import com.gyejoldanji.domain.image.entity.Image;
import com.gyejoldanji.domain.image.repository.ImageRepository;
import com.gyejoldanji.domain.image.service.ImageStorageService;
import com.gyejoldanji.domain.jar.dto.JarPageResponse;
import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.domain.jar.repository.JarPageRepository;
import com.gyejoldanji.domain.record.dto.SeasonRecordListItemResponse;
import com.gyejoldanji.domain.record.entity.Record;
import com.gyejoldanji.domain.record.repository.RecordRepository;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 단지 페이지의 기록과 이미지를 일괄 조회해 N+1 없이 반환한다. */
@Service
@RequiredArgsConstructor
public class JarPageQueryService {
    private final JarPageRepository pageRepository;
    private final RecordRepository recordRepository;
    private final ImageRepository imageRepository;
    private final ImageStorageService imageStorageService;

    @Transactional(readOnly = true)
    public JarPageResponse find(Long memberId, int year, SeasonType season, int pageNumber) {
        JarPage page = pageRepository.findByMemberIdAndYearAndSeasonAndPageNumber(memberId, year, season, pageNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.JAR_PAGE_NOT_FOUND));
        List<Record> records = recordRepository.findAllByJarPageIdOrderByRecordDateDescIdDesc(page.getId());
        List<Long> ids = records.stream().map(Record::getId).toList();
        Map<Long, List<Image>> images = ids.isEmpty() ? Collections.emptyMap()
                : imageRepository.findAllByRecordIds(ids).stream()
                        .collect(Collectors.groupingBy(image -> image.getRecord().getId()));
        List<SeasonRecordListItemResponse> items = records.stream()
                .map(record -> SeasonRecordListItemResponse.from(record,
                        images.getOrDefault(record.getId(), List.of()), imageStorageService::issueViewUrl))
                .toList();
        return JarPageResponse.of(page, items);
    }
}
