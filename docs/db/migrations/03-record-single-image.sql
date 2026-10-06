-- Issue #54: 기록당 단일 이미지 계약 전환
-- 적용 전 아래 조회 결과가 0행인지 반드시 확인한다. 결과가 있으면 임의 삭제하지 말고 데이터를 정리한 뒤 재실행한다.
SELECT record_id, COUNT(*) AS image_count
FROM images
GROUP BY record_id
HAVING COUNT(*) <> 1;

-- 신규 API는 촬영/갤러리 출처를 받지 않는다. 기존 값은 보존하고 신규 행은 NULL을 허용한다.
ALTER TABLE images
    MODIFY COLUMN source ENUM('CAMERA', 'GALLERY') NULL;

-- 단일 이미지에서는 표시 순서 유니크 제약 대신 record_id 자체의 유니크 제약으로 불변식을 보장한다.
ALTER TABLE images
    DROP INDEX uk_images_record_order;

-- Hibernate ddl-auto=update가 먼저 생성했을 수 있으므로, 운영 적용 시 존재 여부를 확인하고 없는 경우에만 실행한다.
-- ALTER TABLE images ADD CONSTRAINT uk_images_record UNIQUE (record_id);
