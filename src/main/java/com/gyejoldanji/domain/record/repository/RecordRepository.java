package com.gyejoldanji.domain.record.repository;

import com.gyejoldanji.domain.record.entity.Record;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 기록 저장과 회원 소유권을 포함한 command용 잠금 조회를 제공한다. */
public interface RecordRepository extends JpaRepository<Record, Long> {

    /** 다른 회원의 기록 존재 여부를 노출하지 않도록 ID와 회원 ID를 함께 조건화해 행 잠금 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Record r where r.id = :recordId and r.member.id = :memberId")
    Optional<Record> findOwnedByIdForUpdate(@Param("recordId") Long recordId, @Param("memberId") Long memberId);
}
