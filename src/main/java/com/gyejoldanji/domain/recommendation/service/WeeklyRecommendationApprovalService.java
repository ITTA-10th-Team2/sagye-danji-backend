package com.gyejoldanji.domain.recommendation.service;

import com.gyejoldanji.domain.content.entity.SeasonalContent;
import com.gyejoldanji.domain.content.repository.SeasonalContentRepository;
import com.gyejoldanji.domain.recommendation.client.model.WeeklyRecommendationApprovalRow;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 시트의 승인 체크 상태를 현재 주간 추천 콘텐츠에 반영한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeeklyRecommendationApprovalService {

    private final SeasonalContentRepository repository;

    /** 소재별 승인 상태를 한 트랜잭션으로 반영한다. */
    @Transactional
    public void applyApprovals(List<WeeklyRecommendationApprovalRow> rows) {
        Map<String, List<SeasonalContent>> recommendationsByMaterial = repository
                .findAllByRecommendationStatusTrue()
                .stream()
                .collect(Collectors.groupingBy(SeasonalContent::getMaterial));

        for (WeeklyRecommendationApprovalRow row : rows) {
            List<SeasonalContent> matches = recommendationsByMaterial.getOrDefault(row.material(), List.of());
            if (matches.isEmpty()) {
                log.warn("현재 주간 추천에 포함되지 않은 시트 행을 건너뜁니다. row={}, material={}",
                        row.rowNumber(), row.material());
                continue;
            }
            SeasonalContent content = findUniqueContent(matches);
            if (row.approved()) {
                content.approveRecommendation();
            } else {
                content.rejectRecommendation();
            }
        }
    }

    private SeasonalContent findUniqueContent(List<SeasonalContent> matches) {
        if (matches.size() != 1) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_MATERIAL_NOT_UNIQUE);
        }
        return matches.getFirst();
    }
}
