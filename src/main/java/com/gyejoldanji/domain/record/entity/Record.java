package com.gyejoldanji.domain.record.entity;

import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.global.common.BaseTimeEntity;
import com.gyejoldanji.global.common.enums.SeasonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;
import java.util.Objects;

/** 여러 이미지가 공유하는 날짜와 글을 가진 회원 기록. */
@Entity
@Table(name = "records", indexes = {
        @Index(name = "idx_records_member_date", columnList = "member_id, record_date, id"),
        @Index(name = "idx_records_member_season_date", columnList = "member_id, season, record_date, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Record extends BaseTimeEntity {

    /** 서비스 내부에서 사용하는 기록 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    /** 기록 작성자이며 회원 삭제 시 기록도 삭제된다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, foreignKey = @ForeignKey(name = "fk_records_member"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member member;

    /** 한국 기준으로 표시할 기록 날짜. */
    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    /** 날짜를 기준으로 서버가 산정하여 전달한 계절. */
    @Enumerated(EnumType.STRING)
    @Column(name = "season", nullable = false)
    private SeasonType season;

    /** 기록에 첨부한 글이며 미입력은 null. */
    @Column(name = "memo", length = 100)
    private String memo;

    /** 회원의 날짜·계절·글을 가진 신규 기록을 생성한다. */
    public static Record create(Member member, LocalDate recordDate, SeasonType season, String memo) {
        Record record = new Record();
        record.member = Objects.requireNonNull(member, "member");
        record.recordDate = Objects.requireNonNull(recordDate, "recordDate");
        record.season = Objects.requireNonNull(season, "season");
        record.memo = validateMemo(memo);
        return record;
    }

    /** 저장된 기록의 날짜·계절·글을 함께 수정한다. */
    public void update(LocalDate recordDate, SeasonType season, String memo) {
        LocalDate validatedDate = Objects.requireNonNull(recordDate, "recordDate");
        SeasonType validatedSeason = Objects.requireNonNull(season, "season");
        String validatedMemo = validateMemo(memo);
        this.recordDate = validatedDate;
        this.season = validatedSeason;
        this.memo = validatedMemo;
    }

    /** 기록 글의 최대 길이를 검증한다. */
    private static String validateMemo(String memo) {
        if (memo != null && memo.codePointCount(0, memo.length()) > 100) {
            throw new IllegalArgumentException("기록 글은 100자를 초과할 수 없습니다.");
        }
        return memo;
    }
}
