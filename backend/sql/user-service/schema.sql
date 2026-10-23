-- user-service 스키마(user_schema)의 현재 DDL. Entity를 추가·변경하면 이 파일도 같이 고친다.
-- 통합 테스트(PostgresIntegrationTest)가 이 파일로 테이블을 만든 뒤 Entity와 맞는지 검사한다(ddl-auto=validate).
-- 테이블 이름에 스키마를 붙이지 않는다. 서비스 계정(user_app)으로 적용하면 계정 기본 search_path인 user_schema에 만들어진다.

CREATE TABLE p_users
(
    user_id    UUID         PRIMARY KEY,
    username   VARCHAR(10)  NOT NULL,
    password   VARCHAR(255) NOT NULL,
    name       VARCHAR(50)  NOT NULL,
    role       VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP    NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(100),

    -- 탈퇴 후에도 username 재사용 불가라 부분 인덱스가 아닌 일반 유니크 제약
    CONSTRAINT uk_p_users_username UNIQUE (username),
    CONSTRAINT ck_p_users_role CHECK (role IN ('MASTER', 'USER'))
);
