package com.sparta.ticket.reservation.booking.application.port;

import java.time.Duration;

/**
 * 대체 승인이나 그 뒤 결제 조회에 대한 payment의 답. 정한 답 밖의 답을 만들 수 없게 봉인한다. HTTP 상태와 필드 이름은 어댑터 밖으로
 * 나오지 않는다.
 */
public sealed interface SubstituteAnswer {

    /** PG 결과가 정해졌다. 결과 메시지를 기다리지 않고 바로 반영한다. */
    record Settled(ConfirmOutcome outcome) implements SubstituteAnswer {

        public Settled {
            if (outcome.result() == ConfirmOutcome.Result.UNKNOWN) {
                throw new IllegalArgumentException("정해진 결과만 담는다");
            }
        }
    }

    /** payment가 승인을 받았고 결과는 정해지지 않았다. 승인을 다시 보내지 않고 조회로 결과를 묻는다. */
    record Accepted() implements SubstituteAnswer {}

    /** 조회해 보니 payment가 이 주문의 승인을 받은 적이 없다. 다시 보내는 것이 처음 보내는 것이다. */
    record NotReceived() implements SubstituteAnswer {}

    /** payment가 PG 자리나 허가를 얻지 못해 받지 않고 돌려보냈다. 결제는 그대로이고, retryAfter 뒤에 다시 보낸다. */
    record Busy(Duration retryAfter) implements SubstituteAnswer {}

    /** payment가 이 요청을 받지 않는다. 다시 보내도 같은 답이다. */
    record Rejected(String reason) implements SubstituteAnswer {}

    /** 답을 받지 못했다. payment가 받았는지 모른다. */
    record Unknown(Throwable cause) implements SubstituteAnswer {}
}
