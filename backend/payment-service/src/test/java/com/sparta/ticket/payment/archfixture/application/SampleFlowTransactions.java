package com.sparta.ticket.payment.archfixture.application;

import org.springframework.transaction.annotation.Transactional;

/** TransactionalRuleTest 전용. 트랜잭션 전용 bean은 규칙을 지킨다. */
public class SampleFlowTransactions {

    @Transactional
    public void prepare() {}
}
