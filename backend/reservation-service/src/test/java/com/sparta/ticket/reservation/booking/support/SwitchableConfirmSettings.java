package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.booking.application.port.DrainSettings;
import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmSettings;
import java.time.Duration;

/**
 * 테스트가 승인 방식을 바꿔 가며 쓰는 설정. 통합 테스트는 컨텍스트를 함께 쓰므로 속성 대신 이 빈을 바꾸고, 매 테스트 전에 동기로 되돌린다.
 * 승인 요청이 나가지 못할 때 동기로 돌리는 기능은 운영 기본값처럼 켜 두고, 끈 동작을 보는 테스트만 끈다.
 * Outbox에 남은 요청을 HTTP로 대신 보내는 작업자는 운영 기본값처럼 꺼 두고, 그 동작을 보는 테스트만 켠다.
 */
public class SwitchableConfirmSettings implements PaymentConfirmSettings {

    public static final Duration WINDOW = Duration.ofMinutes(5);
    public static final Duration GRACE = Duration.ofMinutes(1);
    public static final Duration UNSENT_AFTER = Duration.ofSeconds(5);
    public static final int DRAIN_CONCURRENCY = 2;
    public static final Duration RESEND_AFTER = Duration.ofSeconds(2);
    public static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private volatile boolean viaMessage;
    private volatile boolean fallbackEnabled = true;
    private volatile boolean drainEnabled;
    private final DrainSettings drain = new DrainSettings() {
        @Override
        public boolean enabled() {
            return drainEnabled;
        }

        @Override
        public int concurrency() {
            return DRAIN_CONCURRENCY;
        }

        @Override
        public Duration resendAfter() {
            return RESEND_AFTER;
        }

        @Override
        public Duration maxBackoff() {
            return MAX_BACKOFF;
        }
    };

    public void useMessage() {
        viaMessage = true;
    }

    public void disableFallback() {
        fallbackEnabled = false;
    }

    public void enableDrain() {
        drainEnabled = true;
    }

    public void reset() {
        viaMessage = false;
        fallbackEnabled = true;
        drainEnabled = false;
    }

    @Override
    public boolean viaMessage() {
        return viaMessage;
    }

    @Override
    public Duration window() {
        return WINDOW;
    }

    @Override
    public Duration grace() {
        return GRACE;
    }

    @Override
    public boolean fallbackEnabled() {
        return fallbackEnabled;
    }

    @Override
    public Duration unsentAfter() {
        return UNSENT_AFTER;
    }

    @Override
    public DrainSettings drain() {
        return drain;
    }
}
