-- reservation-service 스키마(reservation_schema)의 현재 DDL. Entity를 추가·변경하면 이 파일도 같이 고친다.
-- 통합 테스트(PostgresIntegrationTest)가 이 파일로 테이블을 만든 뒤 Entity와 맞는지 검사한다(ddl-auto=validate).
-- 테이블 이름에 스키마를 붙이지 않는다. 서비스 계정(reservation_app)으로 적용하면 계정 기본 search_path인 reservation_schema에 만들어진다.

-- 예약. booking 모듈이 소유한다(설계서 D12). 상태 이름은 ERD 명세서와 대조 전이다(T3).
-- hold_expires_at을 예약과 좌석 중 어디에 둘지는 S4에서 정한다. 지금은 예약에 둔다.
CREATE TABLE p_reservations
(
    reservation_id  UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL,
    schedule_id     UUID         NOT NULL,
    status          VARCHAR(30)  NOT NULL,
    hold_expires_at TIMESTAMP    NOT NULL,
    -- 마지막 결제 준비의 주문 번호와 할인 후 금액. 결제 시도마다 새로 쓴다
    order_id        VARCHAR(64),
    payment_amount  BIGINT,
    -- 결제 중에만 둔다. 승인 요청이 payment에 닿지 않았을 때 같은 요청을 다시 보내는 데 쓴다
    pg_auth_token   VARCHAR(255),
    created_at      TIMESTAMP    NOT NULL,
    created_by      VARCHAR(100) NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    updated_by      VARCHAR(100) NOT NULL,
    deleted_at      TIMESTAMP,
    deleted_by      VARCHAR(100),

    CONSTRAINT uk_p_reservations_order_id UNIQUE (order_id),
    CONSTRAINT ck_p_reservations_status
        CHECK (status IN ('HOLD', 'PAYMENT_IN_PROGRESS', 'CONFIRMED', 'EXPIRED', 'CANCELLED'))
);

-- 만료 스케줄러가 시간이 지난 HOLD를 고른다
CREATE INDEX ix_p_reservations_hold_expiry ON p_reservations (hold_expires_at) WHERE status = 'HOLD';
-- 결과 확인 스케줄러가 결제 중으로 오래 머문 예약을 고른다
CREATE INDEX ix_p_reservations_payment_stale ON p_reservations (updated_at) WHERE status = 'PAYMENT_IN_PROGRESS';

-- 예약에 묶인 회차 좌석. 좌석 상태는 seat 모듈이 소유하고, 여기에는 id만 둔다
CREATE TABLE p_reservation_seats
(
    reservation_id UUID NOT NULL REFERENCES p_reservations (reservation_id),
    seat_id        UUID NOT NULL,

    CONSTRAINT pk_p_reservation_seats PRIMARY KEY (reservation_id, seat_id)
);
