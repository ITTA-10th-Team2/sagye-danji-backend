package com.gyejoldanji.domain.jar.dto;

import com.gyejoldanji.domain.jar.entity.JarPage;
import com.gyejoldanji.domain.jar.entity.JarSticker;
import com.gyejoldanji.domain.jar.enums.StickerType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 연도·계절별 단지 스티커 배치 응답. */
@Schema(description = "단지 스티커 배치")
public record JarStickerResponse(List<Item> items) {
    public JarStickerResponse { items = List.copyOf(items); }

    /** 저장된 레이아웃을 z-index 순서의 응답으로 변환한다. */
    public static JarStickerResponse from(JarPage page) {
        return new JarStickerResponse(page.getStickers().stream().map(Item::from).toList());
    }

    /** 저장된 배치가 없을 때의 빈 응답. */
    public static JarStickerResponse empty() { return new JarStickerResponse(List.of()); }

    /** 스티커 한 개의 표시 정보. */
    public record Item(StickerType stickerType, double xRatio, double yRatio,
                       double scale, double rotation, int zIndex) {
        private static Item from(JarSticker sticker) {
            return new Item(sticker.getStickerType(), sticker.getXRatio(),
                    sticker.getYRatio(), sticker.getScale(), sticker.getRotation(), sticker.getZIndex());
        }
    }
}
