-- 판매자 공연 명령(초안 등록, 등급별 가격) 전용 fixture. 다른 기능의 fixture에 의존하지 않는다.
-- 공연장 301: 등급 VIP(301)·R(302)·S(303)·삭제 등급(304). 좌석 VIP 301·302, R 303, S 304(공연 310에서 판매 제외), 삭제 좌석 305(R)
-- 공연장 302: 다른 공연장(등급 305, 좌석 306). 공연장 303: 삭제된 공연장
-- 공연 310: 판매자 501의 초안(version 2, 가격 VIP 150000·R 90000·삭제된 이전 S 가격), 311: 판매자 501의 공개 공연,
-- 312: 판매자 502의 초안, 313: 삭제된 공연
-- 공연 310 회차: 320 READY(구성 1), 321 FAILED(구성 2), 322 삭제, 323 취소(READY), 324 PENDING(다음 재시도 시각 있음)
-- 판매 대상 등급 경계: 공연장 304의 공연 314. VIP(311)는 살아 있는 좌석, R(312)은 삭제 좌석만, S(313)는 판매 제외 행이 삭제돼 판매 대상
-- 빈 가격 집합: 공연 315는 공연장 302의 유일한 좌석 306을 판매 제외해 판매 대상 좌석이 없다
INSERT INTO p_venues (id,name,address,region,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (301,'판매 공연장','서울특별시 중구 예시로 10','서울',2,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venues (id,name,address,region,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (302,'다른 공연장','부산 주소','부산',1,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venues (id,name,address,region,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (303,'삭제 공연장','대구 주소','대구',0,'2026-10-01','SYSTEM','2026-10-02','SYSTEM','2026-10-02');

INSERT INTO p_venue_seat_grades (id,venue_id,name,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (301,301,'VIP','2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seat_grades (id,venue_id,name,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (302,301,'R','2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seat_grades (id,venue_id,name,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (303,301,'S','2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seat_grades (id,venue_id,name,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (304,301,'삭제 등급','2026-10-01','SYSTEM','2026-10-02','SYSTEM','2026-10-02');
INSERT INTO p_venue_seat_grades (id,venue_id,name,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (305,302,'VIP','2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);

INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (301,301,301,'A','1',1,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (302,301,301,'A','1',2,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (303,301,302,'B','1',1,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (304,301,303,'C','1',1,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (305,301,302,'B','1',2,'2026-10-01','SYSTEM','2026-10-02','SYSTEM','2026-10-02');
INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (306,302,305,'A','1',1,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);

INSERT INTO p_performances (id,seller_id,venue_id,title,genre,age_rating,running_time,publication_status,operation_status,published_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (310,501,301,'초안 공연','MUSICAL','12',120,'DRAFT','ACTIVE',NULL,2,'2026-10-01','seller01','2026-10-01','seller01',NULL);
INSERT INTO p_performances (id,seller_id,venue_id,title,genre,age_rating,running_time,publication_status,operation_status,published_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (311,501,301,'공개 공연','MUSICAL','12',120,'PUBLISHED','ACTIVE','2026-10-05',3,'2026-10-01','seller01','2026-10-05','seller01',NULL);
INSERT INTO p_performances (id,seller_id,venue_id,title,genre,age_rating,running_time,publication_status,operation_status,published_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (312,502,301,'다른 판매자 공연','PLAY','ALL',90,'DRAFT','ACTIVE',NULL,0,'2026-10-01','seller02','2026-10-01','seller02',NULL);
INSERT INTO p_performances (id,seller_id,venue_id,title,genre,age_rating,running_time,publication_status,operation_status,published_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (313,501,301,'삭제 공연','PLAY','ALL',90,'DRAFT','ACTIVE',NULL,0,'2026-10-01','seller01','2026-10-02','seller01','2026-10-02');

INSERT INTO p_performance_grade_prices (id,performance_id,venue_seat_grade_id,price,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (301,310,301,150000,'2026-10-01','seller01','2026-10-01','seller01',NULL);
INSERT INTO p_performance_grade_prices (id,performance_id,venue_seat_grade_id,price,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (302,310,302,90000,'2026-10-01','seller01','2026-10-01','seller01',NULL);
INSERT INTO p_performance_grade_prices (id,performance_id,venue_seat_grade_id,price,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (303,310,303,50000,'2026-09-30','seller01','2026-10-01','seller01','2026-10-01');

INSERT INTO p_performance_seat_policies (id,performance_id,venue_seat_id,exclusion_reason,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (301,310,304,'운영석','2026-10-01','seller01','2026-10-01','seller01',NULL);

INSERT INTO p_schedules (id,performance_id,start_at,booking_open_at,booking_close_at,operation_status,seat_setup_status,seat_config_version,seat_setup_request_id,seat_setup_error,sales_suspended,seat_setup_attempts,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (320,310,'2026-12-01 19:00:00','2026-11-10 14:00:00','2026-12-01 18:00:00','ACTIVE','READY',1,'req-320',NULL,FALSE,1,0,'2026-10-01','seller01','2026-10-01','seller01',NULL);
INSERT INTO p_schedules (id,performance_id,start_at,booking_open_at,booking_close_at,operation_status,seat_setup_status,seat_config_version,seat_setup_request_id,seat_setup_error,sales_suspended,seat_setup_attempts,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (321,310,'2026-12-02 19:00:00','2026-11-10 14:00:00','2026-12-02 18:00:00','ACTIVE','FAILED',2,'req-321','좌석 준비 실패',FALSE,5,0,'2026-10-01','seller01','2026-10-01','seller01',NULL);
INSERT INTO p_schedules (id,performance_id,start_at,booking_open_at,booking_close_at,operation_status,seat_setup_status,seat_config_version,seat_setup_request_id,seat_setup_error,sales_suspended,seat_setup_attempts,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (322,310,'2026-12-03 19:00:00','2026-11-10 14:00:00','2026-12-03 18:00:00','ACTIVE','READY',1,'req-322',NULL,FALSE,1,0,'2026-10-01','seller01','2026-10-02','seller01','2026-10-02');

INSERT INTO p_schedules (id,performance_id,start_at,booking_open_at,booking_close_at,operation_status,seat_setup_status,seat_config_version,seat_setup_request_id,seat_setup_error,sales_suspended,canceled_at,cancel_reason,seat_setup_attempts,seat_setup_next_attempt_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (323,310,'2026-12-04 19:00:00','2026-11-10 14:00:00','2026-12-04 18:00:00','CANCELED','READY',1,'req-323',NULL,FALSE,'2026-10-20 10:00:00','공연장 사정',1,NULL,1,'2026-10-01','seller01','2026-10-20','seller01',NULL);
INSERT INTO p_schedules (id,performance_id,start_at,booking_open_at,booking_close_at,operation_status,seat_setup_status,seat_config_version,seat_setup_request_id,seat_setup_error,sales_suspended,canceled_at,cancel_reason,seat_setup_attempts,seat_setup_next_attempt_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (324,310,'2026-12-05 19:00:00','2026-11-10 14:00:00','2026-12-05 18:00:00','ACTIVE','PENDING',1,'req-324','일시 장애',FALSE,NULL,NULL,2,'2026-10-30 12:05:00',0,'2026-10-01','seller01','2026-10-01','seller01',NULL);

INSERT INTO p_venues (id,name,address,region,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (304,'경계 공연장','인천 주소','인천',3,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seat_grades (id,venue_id,name,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (311,304,'VIP','2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seat_grades (id,venue_id,name,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (312,304,'R','2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seat_grades (id,venue_id,name,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (313,304,'S','2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (311,304,311,'A','1',1,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (312,304,312,'B','1',1,'2026-10-01','SYSTEM','2026-10-02','SYSTEM','2026-10-02');
INSERT INTO p_venue_seats (id,venue_id,venue_seat_grade_id,section,row_label,seat_number,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (313,304,313,'C','1',1,'2026-10-01','SYSTEM','2026-10-01','SYSTEM',NULL);
INSERT INTO p_performances (id,seller_id,venue_id,title,genre,age_rating,running_time,publication_status,operation_status,published_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (314,501,304,'경계 공연','CONCERT','ALL',100,'DRAFT','ACTIVE',NULL,0,'2026-10-01','seller01','2026-10-01','seller01',NULL);
INSERT INTO p_performance_seat_policies (id,performance_id,venue_seat_id,exclusion_reason,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (311,314,313,'이전 제외','2026-10-01','seller01','2026-10-02','seller01','2026-10-02');

INSERT INTO p_performances (id,seller_id,venue_id,title,genre,age_rating,running_time,publication_status,operation_status,published_at,version,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (315,501,302,'판매 좌석 없는 공연','CONCERT','ALL',100,'DRAFT','ACTIVE',NULL,0,'2026-10-01','seller01','2026-10-01','seller01',NULL);
INSERT INTO p_performance_seat_policies (id,performance_id,venue_seat_id,exclusion_reason,created_at,created_by,updated_at,updated_by,deleted_at) VALUES (312,315,306,'전석 제외','2026-10-01','seller01','2026-10-01','seller01',NULL);
