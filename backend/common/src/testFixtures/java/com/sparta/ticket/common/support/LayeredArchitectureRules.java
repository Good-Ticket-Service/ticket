package com.sparta.ticket.common.support;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 업무 서비스 내부 계층(presentation / application / domain / infrastructure)의 의존 방향 규칙.
 * 서비스 테스트에서 {@code @ArchTest static final ArchTests layers = ArchTests.in(LayeredArchitectureRules.class);}로 불러 쓴다.
 * Note: 글로 적은 규칙은 리뷰에서 놓치기 쉬워서 테스트로 강제한다. 도메인 Entity의 JPA 의존은 팀 결정으로 허용한다.
 */
public final class LayeredArchitectureRules {

    private LayeredArchitectureRules() {}

    @ArchTest
    public static final ArchRule DOMAIN_DEPENDS_ON_NO_OTHER_LAYER = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application..", "..infrastructure..", "..presentation..")
            .because("업무 규칙은 유스케이스·기술 구현·HTTP 형식이 바뀌어도 그대로여야 한다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule DOMAIN_DEPENDS_ON_NO_WEB_OR_REMOTE_CALL = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web..",
                    "jakarta.servlet..",
                    "org.springframework.cloud.openfeign..",
                    "feign..",
                    "org.springframework.kafka..",
                    "org.springframework.data.redis..")
            .because("HTTP·원격 호출·메시징·캐시는 infrastructure의 일이다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule APPLICATION_DEPENDS_ON_NO_PRESENTATION_OR_INFRASTRUCTURE = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..presentation..", "..infrastructure..")
            .because("유스케이스는 HTTP 형식과 기술 구현을 모르고, 필요한 기능은 application·domain의 인터페이스로 받는다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule APPLICATION_DEPENDS_ON_NO_WEB = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
            .because("요청·응답 형식은 presentation에서 유스케이스 입력·출력으로 바꾼다")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule ONLY_PRESENTATION_DEPENDS_ON_PRESENTATION = noClasses()
            .that()
            .resideInAnyPackage("..domain..", "..application..", "..infrastructure..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..presentation..")
            .because("Controller·HTTP DTO는 바깥 계층이라 다른 계층이 참조하지 않는다")
            .allowEmptyShould(true);
}
