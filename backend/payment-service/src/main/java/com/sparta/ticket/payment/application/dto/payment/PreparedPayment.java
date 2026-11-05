package com.sparta.ticket.payment.application.dto.payment;

import com.sparta.ticket.payment.domain.model.payment.Payment;

/** 신규 생성과 함께 PG 준비 실행권을 저장했는지 구분한다. 재현 요청에는 실행권을 넘기지 않는다. */
public record PreparedPayment(Payment payment, boolean pgReadyClaimed) {}
