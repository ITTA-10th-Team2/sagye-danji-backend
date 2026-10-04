package com.gyejoldanji.domain.image.entity;

import com.gyejoldanji.domain.image.enums.PhotoSource;
import com.gyejoldanji.domain.record.entity.Record;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.Objects;
import java.time.LocalDateTime;

/** 기록에 첨부된 이미지의 저장 위치와 표시 순서를 관리한다. */
@Entity
@Table(name = "images", uniqueConstraints = {
        @UniqueConstraint(name = "uk_images_original_key", columnNames = "original_key"),
        @UniqueConstraint(name = "uk_images_record_order", columnNames = {"record_id", "sort_order"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Image {

    /** 서비스 내부에서 사용하는 이미지 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    /** 이미지가 속한 기록이며 기록 삭제 시 함께 삭제된다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "record_id", nullable = false, foreignKey = @ForeignKey(name = "fk_images_record"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Record record;

    /** 원본 파일의 스토리지 객체 키. */
    @Column(name = "original_key", nullable = false, length = 512)
    private String originalKey;

    /** 썸네일 객체 키이며 없으면 null. */
    @Column(name = "thumbnail_key", length = 512)
    private String thumbnailKey;

    /** 이미지 촬영 또는 선택 경로. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private PhotoSource source;

    /** 같은 기록 안에서 표시할 순서. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 최초 저장 시 한 번 기록되는 UTC 생성 시각. */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamp(6)")
    private LocalDateTime createdAt;

    /** 기록에 속하는 신규 이미지 메타데이터를 생성한다. */
    public static Image create(Record record, String originalKey, String thumbnailKey,
                               PhotoSource source, int sortOrder) {
        Image image = new Image();
        image.record = Objects.requireNonNull(record, "record");
        image.originalKey = Objects.requireNonNull(originalKey, "originalKey");
        image.thumbnailKey = thumbnailKey;
        image.source = Objects.requireNonNull(source, "source");
        if (sortOrder < 0) {
            throw new IllegalArgumentException("이미지 표시 순서는 0 이상이어야 합니다.");
        }
        image.sortOrder = sortOrder;
        return image;
    }

    /** 같은 기록 안에서 이미지의 표시 순서를 변경한다. */
    public void changeSortOrder(int sortOrder) {
        if (sortOrder < 0) {
            throw new IllegalArgumentException("이미지 표시 순서는 0 이상이어야 합니다.");
        }
        this.sortOrder = sortOrder;
    }
}
