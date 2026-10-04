package com.gyejoldanji.domain.recommendation.service;

import com.gyejoldanji.domain.content.entity.SeasonalContent;
import com.gyejoldanji.domain.content.repository.SeasonalContentRepository;
import com.gyejoldanji.global.common.enums.SeasonType;
import com.gyejoldanji.global.common.exception.BusinessException;
import com.gyejoldanji.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** DB에서 이번 주 추천 7개를 무작위로 선정하고 상태를 전이한다. */
@Service
@RequiredArgsConstructor
public class WeeklyRecommendationSelectionService {

    private static final int RECOMMENDATION_COUNT = 7;

    private final SeasonalContentRepository repository;

    /** 기존 추천을 초기화하고 현재 계절 후보 중 새 추천 7개를 선정한다. */
    @Transactional
    public List<SeasonalContent> selectForCurrentWeek() {
        List<SeasonalContent> candidates = new ArrayList<>(repository.findAllBySeason(currentSeason()));
        if (candidates.size() < RECOMMENDATION_COUNT) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_CANDIDATES_INSUFFICIENT);
        }

        repository.findAllByRecommendationStatusTrue()
                .forEach(SeasonalContent::clearRecommendation);

        Collections.shuffle(candidates);
        List<SeasonalContent> selected = candidates.subList(0, RECOMMENDATION_COUNT);
        for (int order = 0; order < selected.size(); order++) {
            selected.get(order).assignRecommendation(order);
        }
        return selected.stream()
                .sorted(Comparator.comparingInt(SeasonalContent::getRecommendationOrder))
                .toList();
    }

    /** MVP에서는 가을로 고정하며 추후 날짜 기반 계절 정책으로 교체한다. */
    private SeasonType currentSeason() {
        // TODO: 날짜와 서비스 계절 정책에 따른 자동 계절 판정으로 교체한다.
        return SeasonType.AUTUMN;
    }
}
