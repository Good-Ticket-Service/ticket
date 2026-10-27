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

    @ArchTest
    static final ArchRule SEAT_USES_BOOKING_ONLY_THROUGH_FACADE = noClasses()
            .that()
            .resideInAPackage("com.sparta.ticket.reservation.seat..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.sparta.ticket.reservation.booking.domain..",
                    "com.sparta.ticket.reservation.booking.infrastructure..")
            .because("seat는 BookingFacade와 그 요청·응답 타입(booking.application)으로만 booking을 부른다. Feign으로 바꿀 때 그대로 DTO가 된다")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule BOOKING_USES_SEAT_ONLY_THROUGH_FACADE = noClasses()
            .that()
            .resideInAPackage("com.sparta.ticket.reservation.booking..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.sparta.ticket.reservation.seat.domain..",
                    "com.sparta.ticket.reservation.seat.infrastructure..")
            .because("booking은 SeatFacade와 그 요청·응답 타입으로만 seat를 부른다")
            .allowEmptyShould(true);
}
