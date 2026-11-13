package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmSettings;
import java.time.Duration;

/**
 * 테스트가 승인 방식을 바꿔 가며 쓰는 설정. 통합 테스트는 컨텍스트를 함께 쓰므로 속성 대신 이 빈을 바꾸고, 매 테스트 전에 동기로 되돌린다.
 * 승인 요청이 나가지 못할 때 동기로 돌리는 기능은 운영 기본값처럼 켜 두고, 끈 동작을 보는 테스트만 끈다.
 */
public class SwitchableConfirmSettings implements PaymentConfirmSettings {

    public static final Duration WINDOW = Duration.ofMinutes(5);
    public static final Duration GRACE = Duration.ofMinutes(1);
    public static final Duration UNSENT_AFTER = Duration.ofSeconds(5);

    private volatile boolean viaMessage;
    private volatile boolean fallbackEnabled = true;

    public void useMessage() {
        viaMessage = true;
    }

    public void disableFallback() {
        fallbackEnabled = false;
    }

    public void reset() {
        viaMessage = false;
        fallbackEnabled = true;
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
}
