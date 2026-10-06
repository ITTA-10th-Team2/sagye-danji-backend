package com.gyejoldanji.domain.jar.entity;

import com.gyejoldanji.domain.jar.enums.StickerType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

/** 단지 화면에 배치된 스티커 한 개의 정규화된 위치·크기·회전 정보. */
@Entity
@Table(name = "jar_page_stickers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_jar_sticker_z_index", columnNames = {"jar_page_id", "z_index"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JarSticker {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "jar_page_id", nullable = false, foreignKey = @ForeignKey(name = "fk_jar_sticker_page"))
    private JarPage jarPage;
    @Enumerated(EnumType.STRING) @Column(name = "sticker_type", nullable = false, length = 32)
    private StickerType stickerType;
    @Column(name = "x_ratio", nullable = false) private double xRatio;
    @Column(name = "y_ratio", nullable = false) private double yRatio;
    @Column(name = "scale_value", nullable = false) private double scale;
    @Column(name = "rotation", nullable = false) private double rotation;
    @Column(name = "z_index", nullable = false) private int zIndex;

    static JarSticker create(JarPage jarPage, Spec spec) {
        JarSticker sticker = new JarSticker();
        sticker.jarPage = Objects.requireNonNull(jarPage, "jarPage");
        sticker.stickerType = spec.stickerType();
        sticker.xRatio = spec.xRatio();
        sticker.yRatio = spec.yRatio();
        sticker.scale = spec.scale();
        sticker.rotation = spec.rotation();
        sticker.zIndex = spec.zIndex();
        return sticker;
    }

    /** 요청 DTO와 영속 엔티티 사이의 불변 값 객체. */
    public record Spec(StickerType stickerType, double xRatio, double yRatio,
                       double scale, double rotation, int zIndex) { }
}
