package com.sparta.ticket.payment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.sparta.ticket.common.support.LayeredArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

@AnalyzeClasses(packages = "com.sparta.ticket.payment", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchTests LAYERS = ArchTests.in(LayeredArchitectureRules.class);

    private static final List<String> TRANSACTIONAL_ANNOTATIONS =
            List.of("org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional");

    /**
     * Note: 흐름 클래스(PaymentService, PaymentProcessor 등)에 @Transactional이 붙으면 안쪽 트랜잭션이 바깥에 합류해
     * PG 호출이 트랜잭션 안으로 들어가고, PG를 부르기 전에 커밋해야 하는 IN_PROGRESS가 커밋되지 않는다.
     * 그래서 트랜잭션 경계는 이름이 Transactions로 끝나는 전용 bean에만 둔다(docs/architecture.md의 트랜잭션 경계).
     */
    @ArchTest
    static final ArchRule TRANSACTIONAL_ONLY_IN_TRANSACTIONS_CLASSES = classes()
            .that()
            .resideInAPackage("..application..")
            .and()
            .haveSimpleNameNotEndingWith("Transactions")
            .should(notUseTransactional())
            .because("PG 호출은 트랜잭션 밖에 있어야 하고, 상태는 PG 호출 전에 커밋돼야 한다")
            .allowEmptyShould(true);

    /**
     * 정산은 결제가 넘긴 스냅샷(SaleSource)만 쓴다(settlement-design 4.1절). 결제·환불 Entity나 Repository를 직접 읽으면
     * 정산 쪽 변경이 결제 잠금 순서·상태 전이에 끼어들 수 있어 막는다.
     */
    @ArchTest
    static final ArchRule SETTLEMENT_USES_SNAPSHOT_ONLY = noClasses()
            .that()
            .resideInAnyPackage("..settlement..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..domain.model.payment..",
                    "..domain.model.refund..",
                    "..domain.repository.payment..",
                    "..domain.repository.refund..")
            .because("정산은 결제·환불 Entity를 직접 참조하지 않고 결제가 넘긴 스냅샷만 쓴다");

    private static ArchCondition<JavaClass> notUseTransactional() {
        return new ArchCondition<>("not be annotated with @Transactional on the class or its methods") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (hasTransactional(javaClass)) {
                    events.add(
                            SimpleConditionEvent.violated(javaClass, javaClass.getName() + " 클래스에 @Transactional이 있다"));
                }
                for (JavaMethod method : javaClass.getMethods()) {
                    if (TRANSACTIONAL_ANNOTATIONS.stream().anyMatch(method::isAnnotatedWith)) {
                        events.add(SimpleConditionEvent.violated(
                                method, method.getFullName() + " 메서드에 @Transactional이 있다"));
                    }
                }
            }
        };
    }

    private static boolean hasTransactional(JavaClass javaClass) {
        return TRANSACTIONAL_ANNOTATIONS.stream().anyMatch(javaClass::isAnnotatedWith);
    }
}
