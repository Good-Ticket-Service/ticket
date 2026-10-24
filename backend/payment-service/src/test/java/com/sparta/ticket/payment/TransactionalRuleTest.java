package com.sparta.ticket.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.payment.archfixture.application.SampleFlowService;
import com.sparta.ticket.payment.archfixture.application.SampleFlowTransactions;
import com.sparta.ticket.payment.archfixture.application.SampleTransactionalFlow;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실제 코드에 흐름 클래스가 생기기 전에도 규칙이 동작하는지, 테스트 전용 클래스로 확인한다. */
class TransactionalRuleTest {

    @Test
    @DisplayName("흐름 클래스의 메서드에 @Transactional이 있으면 규칙을 위반한다")
    void rejectsTransactionalMethodInFlowClass() {
        JavaClasses classes = new ClassFileImporter().importClasses(SampleFlowService.class);

        assertThat(ArchitectureTest.TRANSACTIONAL_ONLY_IN_TRANSACTIONS_CLASSES
                        .evaluate(classes)
                        .hasViolation())
                .isTrue();
    }

    @Test
    @DisplayName("흐름 클래스 자체에 @Transactional이 있어도 규칙을 위반한다")
    void rejectsTransactionalOnFlowClass() {
        JavaClasses classes = new ClassFileImporter().importClasses(SampleTransactionalFlow.class);

        assertThat(ArchitectureTest.TRANSACTIONAL_ONLY_IN_TRANSACTIONS_CLASSES
                        .evaluate(classes)
                        .hasViolation())
                .isTrue();
    }

    @Test
    @DisplayName("이름이 Transactions로 끝나는 클래스에는 @Transactional을 쓸 수 있다")
    void allowsTransactionalInTransactionsClass() {
        JavaClasses classes = new ClassFileImporter().importClasses(SampleFlowTransactions.class);

        assertThat(ArchitectureTest.TRANSACTIONAL_ONLY_IN_TRANSACTIONS_CLASSES
                        .evaluate(classes)
                        .hasViolation())
                .isFalse();
    }
}
