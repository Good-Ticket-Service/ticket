package com.sparta.ticket.reservation.support;

import com.sparta.ticket.common.support.PostgresIntegrationTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * reservation-service 통합 테스트의 공통 부모. 모든 통합 테스트가 이 클래스를 상속해 같은 설정을 쓴다.
 *
 * Note: PostgresIntegrationTest는 컨테이너를 하나만 띄워 공유하지만, schema.sql은 Spring 컨텍스트가 뜰 때마다 실행된다.
 * 테스트 클래스마다 @Import나 @TestPropertySource가 다르면 컨텍스트가 따로 떠서 두 번째 CREATE TABLE이 "이미 있음"으로 실패한다.
 * 설정을 한곳에 모아 컨텍스트를 하나로 유지한다.
 * open-in-view는 운영(config-repo/application.yml)과 같이 끈다. 켜 두면 요청 내내 DB 연결을 잡아
 * "원격 호출은 트랜잭션 밖" 검사가 운영과 다른 조건에서 통과한다.
 */
@AutoConfigureMockMvc
@Import(BookingTestConfig.class)
@TestPropertySource(properties = {"reservation.booking.scheduler.enabled=false", "spring.jpa.open-in-view=false"})
public abstract class ReservationIntegrationTest extends PostgresIntegrationTest {}
