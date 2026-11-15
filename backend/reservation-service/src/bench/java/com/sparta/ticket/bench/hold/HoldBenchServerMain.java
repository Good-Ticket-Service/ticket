package com.sparta.ticket.bench.hold;

import com.sparta.ticket.reservation.ReservationServiceApplication;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldMode;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldModeSwitch;
import com.sparta.ticket.reservation.seat.application.hold.SeatHoldProperties;
import com.sparta.ticket.reservation.seat.application.port.SaleValidation;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationPort;
import com.sparta.ticket.reservation.seat.application.port.SaleValidationSeat;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * #434 좌석 선점 처리량 측정용 예매 서비스 기동기. 운영 설정(config-repo의 application.yml·reservation-service.yml)을 파일로 읽고,
 * config-server·Eureka 없이 뜬다. DB·Kafka·Redis 주소와 기본 연결 풀 크기는 실행 스크립트가 환경 변수로 넘긴다.
 *
 * 판매 검증만 공연 서비스 대신 아래 가짜를 쓴다. #390 통합시험에서 판매 검증 서버 쪽 p99가 5~12ms였으므로 요청 스레드를
 * {@link #SALE_VALIDATION_DELAY}만큼 붙잡은 뒤 허용한다. 가짜가 바로 응답하면 스레드 점유가 실제보다 짧아 풀 크기 효과가 달라진다.
 *
 * Note: 이 클래스는 com.sparta.ticket.reservation 밖에 두어 앱의 컴포넌트 스캔에 걸리지 않는다. 가짜 빈은 기동 소스로 직접 넘긴다.
 *
 * 인자: backend 폴더 경로
 */
public final class HoldBenchServerMain {

    // 측정마다 -Dbench.sale.delay.ms로 바꾼다(모의 매진은 10ms: #390 판매 검증 서버 쪽 p99 5\~12ms의 위쪽)
    static final Duration SALE_VALIDATION_DELAY = Duration.ofMillis(Integer.getInteger("bench.sale.delay.ms", 5));

    private HoldBenchServerMain() {}

    public static void main(String[] args) {
        Path backend = Path.of(args[0]).toAbsolutePath();
        Path config = backend.resolve("config-repo");
        Path sql = backend.resolve("sql/reservation-service");
        String schema = String.join(
                ",",
                "file:" + sql.resolve("schema.sql"),
                "file:" + sql.resolve("booking.sql"),
                "file:" + sql.resolve("seat.sql"),
                "file:" + sql.resolve("coupon.sql"));
        SpringApplication app = new SpringApplication(
                ReservationServiceApplication.class,
                BenchSaleValidation.class,
                BenchHoldMode.class,
                BenchHoldModeController.class);
        app.setAdditionalProfiles("bench");
        app.run(
                "--spring.cloud.config.enabled=false",
                "--eureka.client.enabled=false",
                "--spring.config.additional-location=file:" + config.resolve("application.yml") + ",file:"
                        + config.resolve("reservation-service.yml"),
                // 스크립트가 측정마다 빈 스키마를 만들고, 앱이 운영과 같은 순서로 DDL을 적용한다(ddl-auto=validate)
                "--spring.sql.init.mode=always",
                "--spring.sql.init.schema-locations=" + schema,
                "--logging.level.root=WARN",
                "--logging.level.org.springframework.boot.web.embedded.tomcat=INFO");
    }

    /** 공연 서비스 판매 검증 대신 쓰는 가짜. 지연 뒤 요청 좌석을 모두 허용한다. */
    @Configuration(proxyBeanMethods = false)
    static class BenchSaleValidation {

        @Bean
        @Primary
        SaleValidationPort benchSaleValidationPort() {
            return (scheduleId, configVersion, venueSeatIds) -> {
                LockSupport.parkNanos(SALE_VALIDATION_DELAY.toNanos());
                List<SaleValidationSeat> seats = venueSeatIds.stream()
                        .map(id -> new SaleValidationSeat(id, 7L, "VIP", 150_000))
                        .toList();
                return new SaleValidation(
                        true,
                        null,
                        scheduleId,
                        11L,
                        1001L,
                        configVersion,
                        1L,
                        LocalDateTime.of(2026, 12, 31, 19, 0),
                        "측정 공연",
                        "MUSICAL",
                        seats);
            };
        }
    }

    /**
     * 선점 위치 스위치(#449)를 측정 중에 바꿀 수 있게 한다. 운영 스위치는 기동 때 정해져, 모드마다 서버를 다시 띄우면 JVM 예열 상태가
     * 달라 두 모드 비교가 흔들린다. 같은 서버에서 회차마다 모드를 바꿔 번갈아 잰다. 기본값은 기동 설정(reservation.seat.hold.mode)이다.
     */
    @Configuration(proxyBeanMethods = false)
    static class BenchHoldMode {

        @Bean
        @Primary
        BenchHoldModeSwitch benchHoldModeSwitch(SeatHoldProperties properties) {
            return new BenchHoldModeSwitch(properties);
        }
    }

    static class BenchHoldModeSwitch extends SeatHoldModeSwitch {

        private volatile SeatHoldMode mode;

        BenchHoldModeSwitch(SeatHoldProperties properties) {
            super(properties);
            this.mode = properties.mode();
        }

        @Override
        public SeatHoldMode current() {
            return mode;
        }

        void set(SeatHoldMode mode) {
            this.mode = mode;
        }
    }

    /** 부하 생성기가 회차를 시작하기 전에 부른다. 측정 서버에만 있다. */
    @RestController
    static class BenchHoldModeController {

        private final BenchHoldModeSwitch modeSwitch;

        BenchHoldModeController(BenchHoldModeSwitch modeSwitch) {
            this.modeSwitch = modeSwitch;
        }

        @PostMapping("/bench/hold-mode")
        String set(@RequestParam SeatHoldMode mode) {
            modeSwitch.set(mode);
            return mode.name();
        }
    }
}
