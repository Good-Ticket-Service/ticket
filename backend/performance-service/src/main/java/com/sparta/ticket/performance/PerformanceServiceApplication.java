package com.sparta.ticket.performance;

import com.sparta.ticket.performance.infrastructure.command.SeatSetupRecoveryCommand;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 공연·회차·좌석 정보를 관리한다. */
@SpringBootApplication
public class PerformanceServiceApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--seat-setup-recover")) {
            SeatSetupRecoveryCommand.run(args);
            return;
        }
        SpringApplication.run(PerformanceServiceApplication.class, args);
    }
}
