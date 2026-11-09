package com.sparta.ticket.waitingroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/**
 * 대기열(room·순번·입장 토큰)을 관리한다. 데이터는 Redis에만 두고 DB를 쓰지 않는다.
 * Note: common이 JPA를 함께 가져오므로 DB 연결 자동 설정을 끈다. 켜 두면 DB 주소가 없어 기동이 실패한다.
 * common의 JPA 감사·QueryDSL 자동 설정은 EntityManagerFactory가 있을 때만 켜지므로 함께 꺼진다.
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
public class WaitingRoomServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaitingRoomServiceApplication.class, args);
    }
}
