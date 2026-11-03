package com.sparta.ticket.performance.infrastructure.command;

import com.sparta.ticket.performance.PerformanceServiceApplication;
import com.sparta.ticket.performance.application.service.SeatSetupRecoveryAttempts;
import java.util.List;
import java.util.Map;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** HTTP 서버 없이 같은 애플리케이션 서비스를 사용해 복구 일정을 접수한다. */
public final class SeatSetupRecoveryCommand {
    private SeatSetupRecoveryCommand() {}

    public static void run(String[] args) {
        DefaultApplicationArguments input = new DefaultApplicationArguments(args);
        long scheduleId = Long.parseLong(required(input, "schedule-id"));
        long version = Long.parseLong(required(input, "config-version"));
        if (scheduleId <= 0 || version <= 0) {
            throw new IllegalArgumentException("회차 ID와 구성 버전은 양수여야 합니다.");
        }
        String requestId = required(input, "request-id");
        String operator = required(input, "operator");
        String reason = required(input, "reason");
        SpringApplication application = new SpringApplication(PerformanceServiceApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "seat-setup-recovery-command",
                        Map.of(
                                "spring.jpa.hibernate.ddl-auto", "validate",
                                "spring.sql.init.mode", "never",
                                "performance.seat-setup.retry.enabled", "false",
                                "performance.seat-setup.recovery.enabled", "false",
                                "eureka.client.enabled", "false"))));
        try (ConfigurableApplicationContext context = application.run(args)) {
            boolean queued = context.getBean(SeatSetupRecoveryAttempts.class)
                    .queue(scheduleId, requestId, version, operator, reason);
            System.out.println(queued ? "복구 확인을 접수했습니다. 회차 상태로 완료를 확인하세요." : "이미 준비 완료이거나 복구 확인이 예약·진행 중입니다.");
        }
    }

    private static String required(DefaultApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("필수 명령 인수를 확인하세요: " + name);
        }
        return values.get(0);
    }
}
