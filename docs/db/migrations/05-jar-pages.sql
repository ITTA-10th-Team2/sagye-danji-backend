-- 기존 임시 jar_layouts/jar_stickers는 보존하고, 실제 기록 페이지용 테이블을 별도로 생성한다.
CREATE TABLE jar_pages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    page_year INT NOT NULL,
    season VARCHAR(16) NOT NULL,
    page_number INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_jar_page_member_year_season_number
        UNIQUE (member_id, page_year, season, page_number),
    CONSTRAINT fk_jar_page_member FOREIGN KEY (member_id) REFERENCES members (id)
);

ALTER TABLE records ADD COLUMN jar_page_id BIGINT NULL AFTER member_id;

CREATE TEMPORARY TABLE record_jar_page_map AS
SELECT id AS record_id,
       member_id,
       YEAR(record_date) AS page_year,
       season,
       FLOOR((ROW_NUMBER() OVER (
           PARTITION BY member_id, YEAR(record_date), season
           ORDER BY record_date ASC, id ASC
       ) - 1) / 5) AS page_number
FROM records;

INSERT INTO jar_pages (member_id, page_year, season, page_number)
SELECT DISTINCT member_id, page_year, season, page_number
FROM record_jar_page_map;

UPDATE records r
JOIN record_jar_page_map m ON m.record_id = r.id
JOIN jar_pages p ON p.member_id = m.member_id
    AND p.page_year = m.page_year
    AND p.season = m.season
    AND p.page_number = m.page_number
SET r.jar_page_id = p.id;

DROP TEMPORARY TABLE record_jar_page_map;

ALTER TABLE records
    MODIFY COLUMN jar_page_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_records_jar_page FOREIGN KEY (jar_page_id) REFERENCES jar_pages (id),
    ADD INDEX idx_records_jar_page (jar_page_id, record_date, id);

CREATE TABLE jar_page_stickers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    jar_page_id BIGINT NOT NULL,
    sticker_type VARCHAR(32) NOT NULL,
    x_ratio DOUBLE NOT NULL,
    y_ratio DOUBLE NOT NULL,
    scale_value DOUBLE NOT NULL,
    rotation DOUBLE NOT NULL,
    z_index INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_jar_sticker_page FOREIGN KEY (jar_page_id) REFERENCES jar_pages (id),
    CONSTRAINT uk_jar_sticker_z_index UNIQUE (jar_page_id, z_index)
);

-- 검증 후 별도 승인된 정리 작업에서만 기존 jar_layouts/jar_stickers를 제거한다.
