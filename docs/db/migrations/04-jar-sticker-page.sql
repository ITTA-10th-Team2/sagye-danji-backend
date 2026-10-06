-- Issue #54 보완: 단지 페이지별 스티커 배치와 UUID 제거
ALTER TABLE jar_layouts
    ADD COLUMN page_number INT NOT NULL DEFAULT 0 AFTER season;

ALTER TABLE jar_layouts
    DROP INDEX uk_jar_layout_member_year_season,
    ADD CONSTRAINT uk_jar_layout_member_year_season_page
        UNIQUE (member_id, layout_year, season, page_number);

ALTER TABLE jar_stickers
    DROP INDEX uk_jar_sticker_instance,
    DROP COLUMN instance_id;
