-- 판매자 회차 등록·좌석 준비 전용 fixture. 다른 기능의 fixture에 의존하지 않는다. 기준 시각은 2026-11-01 12:00(KST)이다.
-- 공연장 401: 등급 VIP(401)·R(402). 좌석 VIP 401·402, R 403(공연 410에서 판매 제외), 삭제 좌석 404(VIP)
-- 공연장 402: 좌석이 없는 공연장
-- 공연 410: 판매자 601의 초안(가격 VIP 150000·R 90000), 411: 판매자 602의 공연, 412: 판매자 601의 취소된 공개 공연,
-- 413: 삭제된 공연, 414: 판매자 601의 초안(가격 없음), 415: 판매자 601의 초안(공연장 402, 좌석 없음)
-- 공연 410 회차: 420(2026-12-01 19:00, READY), 421 삭제(2026-12-02 19:00)
INSERT INTO p_venues (id,name,address,region,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (401,'회차 공연장','서울특별시 중구 예시로 20','서울',1,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venues (id,name,address,region,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (402,'빈 공연장','부산 주소','부산',0,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);

INSERT INTO p_venue_seat_grades (id,venue_id,name,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (401,401,'VIP','2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seat_grades (id,venue_id,name,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (402,401,'R','2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);

INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (401,401,401,'A','1',1,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (402,401,401,'A','1',2,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (403,401,402,'B','2',1,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (404,401,401,'A','1',3,'2026-10-01','SYSTEM','2026-10-02','SYSTEM','2026-10-02');

INSERT INTO p_performances (id,seller_id,venue_id,title,genre,age_rating,running_time,publication_status,operation_status,published_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (410,601,401,'회차 공연','MUSICAL','12',120,'DRAFT','ACTIVE',NULL,1,'2026-10-01','seller601','2026-10-01','seller601',NULL);
INSERT INTO p_performances (id,seller_id,venue_id,title,genre,age_rating,running_time,publication_status,operation_status,published_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (411,602,401,'다른 판매자 공연','PLAY','ALL',90,'DRAFT','ACTIVE',NULL,0,'2026-10-01','seller602','2026-10-01','seller602',NULL);
INSERT INTO p_performances (id,seller_id,venue_id,title,genre,age_rating,running_time,publication_status,operation_status,published_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (412,601,401,'취소 공연','PLAY','ALL',90,'PUBLISHED','CANCELED','2026-10-05',2,'2026-10-01','seller601','2026-10-10','seller601',NULL);
INSERT INTO p_performances (id,seller_id,venue_id,title,genre,age_rating,running_time,publication_status,operation_status,published_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (413,601,401,'삭제 공연','PLAY','ALL',90,'DRAFT','ACTIVE',NULL,0,'2026-10-01','seller601','2026-10-02','seller601','2026-10-02');
INSERT INTO p_performances (id,seller_id,venue_id,title,genre,age_rating,running_time,publication_status,operation_status,published_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (414,601,401,'가격 없는 공연','CONCERT','ALL',100,'DRAFT','ACTIVE',NULL,0,'2026-10-01','seller601','2026-10-01','seller601',NULL);
INSERT INTO p_performances (id,seller_id,venue_id,title,genre,age_rating,running_time,publication_status,operation_status,published_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (415,601,402,'좌석 없는 공연','CONCERT','ALL',100,'DRAFT','ACTIVE',NULL,0,'2026-10-01','seller601','2026-10-01','seller601',NULL);

INSERT INTO p_performance_grade_prices (id,performance_id,venue_seat_grade_id,price,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (401,410,401,150000,'2026-10-01','seller601','2026-10-01','seller601',NULL);
INSERT INTO p_performance_grade_prices (id,performance_id,venue_seat_grade_id,price,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (402,410,402,90000,'2026-10-01','seller601','2026-10-01','seller601',NULL);

INSERT INTO p_performance_seat_policies (id,performance_id,venue_seat_id,exclusion_reason,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (401,410,403,'운영석','2026-10-01','seller601','2026-10-01','seller601',NULL);

INSERT INTO p_schedules (id,performance_id,start_at,booking_open_at,booking_close_at,operation_status,seat_setup_status,seat_config_version,seat_setup_request_id,seat_setup_error,sales_suspended,seat_setup_attempts,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (420,410,'2026-12-01 19:00:00','2026-11-10 14:00:00','2026-12-01 18:00:00','ACTIVE','READY',1,'req-420',NULL,FALSE,1,0,'2026-10-01','seller601','2026-10-01','seller601',NULL);
INSERT INTO p_schedules (id,performance_id,start_at,booking_open_at,booking_close_at,operation_status,seat_setup_status,seat_config_version,seat_setup_request_id,seat_setup_error,sales_suspended,seat_setup_attempts,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (421,410,'2026-12-02 19:00:00','2026-11-10 14:00:00','2026-12-02 18:00:00','ACTIVE','READY',1,'req-421',NULL,FALSE,1,0,'2026-10-01','seller601','2026-10-02','seller601','2026-10-02');
