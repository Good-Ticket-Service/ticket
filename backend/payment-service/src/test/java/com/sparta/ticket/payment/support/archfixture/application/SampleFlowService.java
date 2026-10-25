package com.sparta.ticket.payment.support.archfixture.application;

import org.springframework.transaction.annotation.Transactional;

/** TransactionalRuleTest 전용. 규칙을 어기는 흐름 클래스. */
public class SampleFlowService {

    @Transactional
    public void process() {}
}
