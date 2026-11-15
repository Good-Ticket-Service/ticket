package com.sparta.ticket.reservation.seat.application.hold;

import java.util.regex.Pattern;

/**
 * Redis 선점 ID 형식(#449). "h_" + 16진수 32자(무작위 128비트)다.
 * Note: 경로로 받은 값이 Redis 키 이름에 들어가므로 형식을 먼저 확인한다. 형식이 다르면 Redis를 부르지 않는다.
 */
public final class HoldIds {

    private static final Pattern FORMAT = Pattern.compile("^h_[0-9a-f]{32}$");

    private HoldIds() {}

    public static boolean isHoldId(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }
}
