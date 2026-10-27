package com.sparta.ticket.reservation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.sparta.ticket.common.support.LayeredArchitectureRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.sparta.ticket.reservation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchTests LAYERS = ArchTests.in(LayeredArchitectureRules.class);

    @ArchTest
    static final ArchRule PAYMENT_ADAPTERS_DO_NOT_CHANGE_RESERVATIONS = noClasses()
            .that()
            .resideInAPackage("com.sparta.ticket.reservation.booking.infrastructure.payment..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.sparta.ticket.reservation.booking.infrastructure.persistence..",
                    "com.sparta.ticket.reservation.booking.domain..")
            .because("결제 결과는 PaymentResultApplier 한 곳에서만 예약에 반영한다(booking-decisions.md B7)")
            .allowEmptyShould(true);
}
