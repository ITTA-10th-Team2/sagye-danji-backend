package com.gyejoldanji.domain.image.repository;

import com.gyejoldanji.domain.image.entity.Image;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** 기록 이미지의 일괄 저장·삭제와 command용 잠금 조회를 제공한다. */
public interface ImageRepository extends JpaRepository<Image, Long> {

    /** 한 기록의 이미지를 표시 순서대로 잠가 수정·삭제 경쟁을 방지한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Image i where i.record.id = :recordId order by i.sortOrder asc")
    List<Image> findAllByRecordIdForUpdate(@Param("recordId") Long recordId);

    /** 주어진 객체 키 중 이미 연결된 키가 하나라도 있는지 확인한다. */
    boolean existsByOriginalKeyIn(Collection<String> originalKeys);
}
