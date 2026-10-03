package com.gyejoldanji.domain.content.repository;

import com.gyejoldanji.domain.content.entity.SeasonalContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 제철 콘텐츠의 영속성 조회와 저장을 담당한다. */
public interface SeasonalContentRepository extends JpaRepository<SeasonalContent, Long> {

    /** 외부 고유 코드로 콘텐츠를 조회한다. */
    Optional<SeasonalContent> findByContentCode(String contentCode);
}
