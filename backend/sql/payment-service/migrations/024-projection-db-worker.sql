-- 기존 B/C 세대의 상태·금액을 바꾸지 않고 DB 워커 세대 D를 허용한다.
ALTER TABLE p_settlement_projection_builds DROP CONSTRAINT p_settlement_projection_builds_mode_check;
ALTER TABLE p_settlement_projection_builds ADD CONSTRAINT p_settlement_projection_builds_mode_check
  CHECK(mode IN ('D','B','C'));
