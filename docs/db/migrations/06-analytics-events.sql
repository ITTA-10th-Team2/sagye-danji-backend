-- 행동 이벤트 원본. 운영 DB에는 앱 배포 전에 수동 적용한다.
-- 앱이 먼저 뜨면 ddl-auto=update가 제약이 다른 테이블을 먼저 만든다.
CREATE TABLE analytics_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_id BIGINT NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    event_name VARCHAR(40) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    service_version VARCHAR(30) NULL,
    os VARCHAR(10) NULL,
    `sequence` INT NULL,
    event_source VARCHAR(10) NOT NULL,
    properties JSON NOT NULL,
    sheet_synced_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_analytics_member_event UNIQUE (member_id, event_id),
    INDEX idx_analytics_member_time (member_id, occurred_at, id),
    INDEX idx_analytics_session_time (session_id, occurred_at, `sequence`),
    INDEX idx_analytics_name_time (event_name, occurred_at),
    INDEX idx_analytics_sheet_pending (sheet_synced_at, id),
    CONSTRAINT ck_analytics_source CHECK (event_source IN ('CLIENT', 'SERVER')),
    CONSTRAINT ck_analytics_os CHECK (os IS NULL OR os IN ('IOS', 'ANDROID', 'WEB', 'OTHER')),
    CONSTRAINT ck_analytics_properties CHECK (JSON_TYPE(properties) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4;
