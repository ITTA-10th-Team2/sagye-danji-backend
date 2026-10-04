package com.gyejoldanji.domain.content.repository;

import com.gyejoldanji.domain.content.entity.SeasonalContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

import com.gyejoldanji.global.common.enums.SeasonType;

/** 제철 콘텐츠의 영속성 조회와 저장을 담당한다. */
public interface SeasonalContentRepository extends JpaRepository<SeasonalContent, Long> {

    /** 외부 고유 코드로 콘텐츠를 조회한다. */
    Optional<SeasonalContent> findByContentCode(String contentCode);

    /** 지정 계절의 주간 추천 후보를 조회한다. */
    List<SeasonalContent> findAllBySeason(SeasonType season);

    /** 현재 주간 추천으로 선정된 콘텐츠를 조회한다. */
    List<SeasonalContent> findAllByRecommendationStatusTrue();

    /** 오늘의 요일 순서에 배정되고 승인된 추천 한 건을 조회한다. */
    Optional<SeasonalContent> findByRecommendationStatusTrueAndRecommendationApprovedTrueAndRecommendationOrder(
            Integer recommendationOrder);

    /** 시트의 소재명으로 콘텐츠를 조회한다. */
    List<SeasonalContent> findAllByMaterial(String material);
}
