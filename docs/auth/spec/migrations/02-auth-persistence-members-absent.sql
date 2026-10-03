-- 02 회원 확장·영속성 — members가 아직 없는 DB용 수동 마이그레이션
-- MySQL >= 8.0.17 / InnoDB. 자동 실행되지 않는다(Flyway·Liquibase·spring.sql.init 없음). 사람이 대상 확인 후 직접 실행한다.
-- 아래 CREATE 3개가 회원·인증 테이블의 목표 정의다. 엔티티 매핑(columnDefinition·고유키·인덱스 이름)을 바꾸면 함께 바꾼다.
--
-- 적용 기록
--   2026-10-03 로컬 전용 MySQL 8.4.11(시작 시 테이블 0개인 로컬 개발 DB)에 적용.
--   같은 날 AuthPersistenceMySqlIntegrationTest용 빈 전용 DB에도 적용해 ddl-auto=validate와 DB 동작 테스트에 사용했다.
--   공유 개발 DB·운영 DB에는 적용하지 않았다.
--
-- [1] 적용 전제 — 아래를 모두 확인한 뒤에만 실행한다.
--   a) 대상 확인: SELECT VERSION(), DATABASE(), @@session.time_zone, @@explicit_defaults_for_timestamp;
--      VERSION >= 8.0.17, DATABASE가 의도한 DB, explicit_defaults_for_timestamp = 1.
--   b) 이 파일이 만들 테이블이 하나도 없어야 한다(0행):
--      SELECT table_name FROM information_schema.tables
--       WHERE table_schema = DATABASE() AND table_name IN ('members','auth_sessions','auth_refresh_tokens');
--      members가 이미 있으면(예: dev 브랜치를 ddl-auto: update로 실행한 DB) 이 파일을 쓰지 않는다.
--      그 경우 기존 행의 status 처리 정책을 정한 뒤 별도 ALTER 마이그레이션을 작성한다.
--   c) mysqldump로 대상 DB를 먼저 백업한다(--single-transaction --routines --triggers --events).
--
-- [2] 적용 순서 — FK 의존 순서대로 members → auth_sessions → auth_refresh_tokens.
--   mysql 클라이언트로 파일을 실행하면 첫 오류에서 멈춘다. --force로 오류를 건너뛰지 않는다.
--   클라이언트 문자셋을 utf8mb4로 지정한다(미지정 시 CHECK 문자열이 _latin1로 저장된다. 동작은 같지만 정의가 달라 보인다).
--   실행 예 — Windows PowerShell, 저장소 루트(sagye-danji-backend)에서, 로컬 Compose 컨테이너 대상:
--     docker cp docs/auth/spec/migrations/02-auth-persistence-members-absent.sql gyejol-danji-mysql-local:/tmp/02-auth.sql
--     if ($LASTEXITCODE -ne 0) { throw "docker cp 실패: $LASTEXITCODE" }
--     docker exec gyejol-danji-mysql-local sh -c 'MYSQL_PWD=$MYSQL_PASSWORD mysql --default-character-set=utf8mb4 -u$MYSQL_USER $MYSQL_DATABASE < /tmp/02-auth.sql'
--     if ($LASTEXITCODE -ne 0) { throw "mysql 실패: $LASTEXITCODE — [3]을 따른다" }
--     docker exec gyejol-danji-mysql-local rm -f /tmp/02-auth.sql
--   - PowerShell은 `< 파일` 입력 리다이렉션을 지원하지 않고, `Get-Content | ` 파이프는 $OutputEncoding으로 다시 인코딩한다.
--     docker cp로 파일 바이트를 그대로 옮기고 컨테이너 안의 sh가 `<`로 넘겨 UTF-8을 보존한다.
--   - 작은따옴표 안의 $MYSQL_* 는 PowerShell이 해석하지 않고 컨테이너 sh가 Compose 환경변수로 채운다.
--     비밀번호를 명령에 적거나 화면에 출력하지 않는다. 사용자는 앱 계정, 대상 DB는 MYSQL_DATABASE(.env의 DB_NAME)다.
--   - 성공하면 $LASTEXITCODE = 0, SQL 오류면 ERROR 메시지와 함께 1이다(2026-10-03 Windows PowerShell 5.1에서 읽기 전용 SELECT로 확인).
--   - [1]의 대상·전제 확인도 SELECT만 담은 파일을 같은 방식으로 실행해 확인한다.
--
-- [3] 중간에 실패했을 때
--   MySQL DDL은 문장마다 자동 커밋되어 ROLLBACK으로 되돌릴 수 없다. 파일 전체를 다시 실행하지 않는다.
--   a) [1]-b 조회로 이미 만들어진 테이블을 확인하고, 정의가 맞으면 남은 CREATE만 개별 실행한다.
--   b) 만들어진 테이블 정의가 틀렸다면 SHOW CREATE TABLE로 차이를 먼저 확인한다.
--   c) 이 파일이 만든 테이블을 걷어 내야 한다면 먼저 각 테이블의 행 수와 참조 관계
--      (information_schema.referential_constraints의 referenced_table_name)를 확인한다.
--      보존할 데이터가 있으면 추가 백업과 복구 계획 없이 진행하지 않는다.
--      데이터·외부 참조가 없을 때만 FK 역순인 auth_refresh_tokens → auth_sessions → members 순서로 정리를 검토한다.
--      records 등 다른 테이블이 members를 참조하면 members를 임의로 제거하지 않는다.
--   d) 적용 전 덤프는 그 시점 상태만 담는다. 테이블이 없던 DB의 덤프를 다시 입력해도 이 파일이 만든 테이블은 지워지지 않고,
--      적용 후 추가된 데이터도 그 덤프로는 되살릴 수 없다. 정리 전에 현재 상태를 새로 백업한다.
--
-- [4] 적용 후 확인
--   SHOW CREATE TABLE members; SHOW CREATE TABLE auth_sessions; SHOW CREATE TABLE auth_refresh_tokens;
--   SHOW FULL COLUMNS FROM members;   -- provider_user_id: utf8mb4_0900_bin
--   SELECT COLLATION_NAME, PAD_ATTRIBUTE FROM information_schema.COLLATIONS
--    WHERE COLLATION_NAME = 'utf8mb4_0900_bin';   -- NO PAD
--   SELECT constraint_name, table_name, delete_rule FROM information_schema.referential_constraints
--    WHERE constraint_schema = DATABASE() AND constraint_name LIKE 'fk_auth_%';   -- 2행, RESTRICT
--   SELECT COUNT(*) FROM information_schema.table_constraints
--    WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK'
--      AND table_name IN ('members','auth_sessions','auth_refresh_tokens');   -- 9
--   Hibernate validate(spring.jpa.hibernate.ddl-auto=validate)는 타입·존재만 본다. collation·CHECK·FK 규칙은 위 조회로 따로 대조한다.

CREATE TABLE members (
    id BIGINT NOT NULL AUTO_INCREMENT,
    identity_provider VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    onboarding_completed_at TIMESTAMP(6) NULL,
    last_login_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_members_provider_user (identity_provider, provider_user_id),
    CONSTRAINT ck_members_provider CHECK (identity_provider = 'TOSS_ANON'),
    CONSTRAINT ck_members_status CHECK (status IN ('ACTIVE','WITHDRAWN','BLOCKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE auth_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_id BIGINT NOT NULL,
    current_refresh_generation INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    revoke_reason VARCHAR(32) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_sessions_key (session_key),
    KEY ix_auth_sessions_member (member_id, revoked_at, expires_at),
    KEY ix_auth_sessions_expiry (expires_at),
    CONSTRAINT fk_auth_sessions_member FOREIGN KEY (member_id) REFERENCES members(id) ON DELETE RESTRICT,
    CONSTRAINT ck_auth_sessions_generation CHECK (current_refresh_generation >= 0),
    CONSTRAINT ck_auth_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_sessions_revoke_reason CHECK (revoke_reason IS NULL OR revoke_reason IN ('LOGOUT','REFRESH_REUSE','MEMBER_WITHDRAWN','MEMBER_BLOCKED')),
    CONSTRAINT ck_auth_sessions_revoke_pair CHECK ((revoked_at IS NULL AND revoke_reason IS NULL) OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE auth_refresh_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    token_hash BINARY(32) NOT NULL,
    generation INT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_refresh_hash (token_hash),
    UNIQUE KEY uk_auth_refresh_generation (session_id, generation),
    CONSTRAINT fk_auth_refresh_session FOREIGN KEY (session_id) REFERENCES auth_sessions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_auth_refresh_generation CHECK (generation >= 0),
    CONSTRAINT ck_auth_refresh_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_refresh_consumed CHECK (consumed_at IS NULL OR consumed_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
