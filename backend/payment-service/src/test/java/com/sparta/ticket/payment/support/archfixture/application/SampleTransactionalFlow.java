package com.sparta.ticket.payment.support.archfixture.application;

import org.springframework.transaction.annotation.Transactional;

/** TransactionalRuleTest 전용. 클래스 전체에 트랜잭션을 거는 흐름 클래스. */
@Transactional
public class SampleTransactionalFlow {

    public void process() {}
}
