package com.gyejoldanji.domain.jar.entity;

import com.gyejoldanji.domain.member.entity.Member;
import com.gyejoldanji.global.common.enums.SeasonType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 회원의 특정 연도·계절에서 최대 다섯 기록과 스티커를 묶는 안정적인 단지 페이지. */
@Entity
@Table(name = "jar_pages", uniqueConstraints = @UniqueConstraint(
        name = "uk_jar_page_member_year_season_number",
        columnNames = {"member_id", "page_year", "season", "page_number"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JarPage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, foreignKey = @ForeignKey(name = "fk_jar_page_member"))
    private Member member;
    @Column(name = "page_year", nullable = false)
    private int year;
    @Enumerated(EnumType.STRING) @Column(name = "season", nullable = false, length = 16)
    private SeasonType season;
    @Column(name = "page_number", nullable = false)
    private int pageNumber;
    @OneToMany(mappedBy = "jarPage", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("zIndex ASC")
    private final List<JarSticker> stickers = new ArrayList<>();

    public static JarPage create(Member member, int year, SeasonType season, int pageNumber) {
        JarPage page = new JarPage();
        page.member = Objects.requireNonNull(member, "member");
        page.year = year;
        page.season = Objects.requireNonNull(season, "season");
        page.pageNumber = pageNumber;
        return page;
    }

    public void replaceStickers(List<JarSticker.Spec> specs) {
        clearStickers();
        addStickers(specs);
    }

    /** 기존 스티커를 모두 제거해 orphanRemoval 대상에 포함한다. */
    public void clearStickers() {
        stickers.clear();
    }

    /** 검증된 최종 배치 스펙을 현재 페이지에 추가한다. */
    public void addStickers(List<JarSticker.Spec> specs) {
        specs.forEach(spec -> stickers.add(JarSticker.create(this, spec)));
    }
}
