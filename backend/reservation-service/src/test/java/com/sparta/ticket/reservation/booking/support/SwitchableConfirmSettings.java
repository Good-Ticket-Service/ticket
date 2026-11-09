package com.sparta.ticket.reservation.booking.support;

import com.sparta.ticket.reservation.booking.application.port.PaymentConfirmSettings;
import java.time.Duration;

/**
 * 테스트가 승인 방식을 바꿔 가며 쓰는 설정. 통합 테스트는 컨텍스트를 함께 쓰므로 속성 대신 이 빈을 바꾸고, 매 테스트 전에 동기로 되돌린다.
 */
public class SwitchableConfirmSettings implements PaymentConfirmSettings {

    public static final Duration WINDOW = Duration.ofMinutes(5);
    public static final Duration GRACE = Duration.ofMinutes(1);

    private volatile boolean viaMessage;

    public void useMessage() {
        viaMessage = true;
    }

    public void reset() {
        viaMessage = false;
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
}
