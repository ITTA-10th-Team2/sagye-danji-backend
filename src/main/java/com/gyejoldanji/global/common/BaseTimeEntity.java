package com.gyejoldanji.global.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 생성·수정 시각을 UTC 기준으로 자동 관리하는 Entity 부모 클래스.
 *
 * <p>ID는 각 도메인 Entity가 직접 소유하며, 생성 시각만 필요한 Entity는 이 클래스를 상속하지 않는다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    /** 최초 저장 시 한 번 기록되는 UTC 생성 시각. */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamp(6)")
    private LocalDateTime createdAt;

    /** 변경 감지 시 갱신되는 UTC 수정 시각. */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp(6)")
    private LocalDateTime updatedAt;
}
