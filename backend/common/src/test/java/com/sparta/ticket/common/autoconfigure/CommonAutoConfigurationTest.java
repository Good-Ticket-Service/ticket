package com.sparta.ticket.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sparta.ticket.common.config.CommonWebMvcConfigurer;
import com.sparta.ticket.common.exception.GlobalExceptionHandler;
import com.sparta.ticket.common.feign.FeignErrorDecoder;
import com.sparta.ticket.common.feign.FeignHeaderInterceptor;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CommonAutoConfigurationTest {

    private static final List<Class<?>> COMMON = List.of(
            CommonWebAutoConfiguration.class, CommonJpaAutoConfiguration.class, CommonQuerydslAutoConfiguration.class,
            CommonFeignAutoConfiguration.class, CommonJacksonAutoConfiguration.class, CommonOpenApiAutoConfiguration.class);

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withConfiguration(AutoConfigurations.of(COMMON.toArray(Class<?>[]::new)))
            .withPropertyValues("spring.application.name=test-service");

    @Test
    @DisplayName("자동 설정 6개가 AutoConfiguration.imports에 등록돼 있다")
    void registeredInImports() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates();

        assertThat(candidates).containsAll(COMMON.stream().map(Class::getName).toList());
    }

    @Test
    @DisplayName("MVC 서비스에는 웹·Feign·Jackson·OpenAPI 공통 빈이 등록된다")
    void registersDefaults() {
        web.run(context -> {
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class)
                    .hasSingleBean(CommonWebMvcConfigurer.class)
                    .hasSingleBean(FeignHeaderInterceptor.class)
                    .hasSingleBean(FeignErrorDecoder.class)
                    .hasSingleBean(OpenAPI.class);
            assertThat(context.getBean(ObjectMapper.class).isEnabled(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)).isTrue();
        });
    }

    @Test
    @DisplayName("JPA가 설정되지 않은 서비스는 Audit·QueryDSL 설정만 빠지고 정상 기동한다")
    void skipsJpaWithoutEntityManagerFactory() {
        web.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(JPAQueryFactory.class)
                .doesNotHaveBean("auditorAware"));
    }

    @Test
    @DisplayName("묶음마다 common.<묶음>.enabled=false로 끌 수 있다")
    void canDisableEachGroup() {
        web.withPropertyValues("common.web.enabled=false", "common.feign.enabled=false",
                        "common.openapi.enabled=false", "common.jackson.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class)
                            .doesNotHaveBean(CommonWebMvcConfigurer.class)
                            .doesNotHaveBean(FeignHeaderInterceptor.class)
                            .doesNotHaveBean(FeignErrorDecoder.class)
                            .doesNotHaveBean(OpenAPI.class);
                    assertThat(context.getBean(ObjectMapper.class).isEnabled(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)).isFalse();
                });
    }

    @Test
    @DisplayName("서비스가 같은 역할의 빈을 직접 정의하면 공통 빈은 빠진다")
    void backsOffForServiceBeans() {
        web.withUserConfiguration(ServiceBeans.class).run(context -> {
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
            assertThat(context.getBean(GlobalExceptionHandler.class)).isInstanceOf(ServiceExceptionHandler.class);
            assertThat(context).hasSingleBean(ErrorDecoder.class).doesNotHaveBean(FeignErrorDecoder.class);
        });
    }

    @Test
    @DisplayName("웹 애플리케이션이 아니면 MVC 공통 설정과 OpenAPI가 빠진다")
    void skipsWebInNonWebContext() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class, CommonOpenApiAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class).doesNotHaveBean(OpenAPI.class));
    }

    @Test
    @DisplayName("Feign이 클래스패스에 없으면 Feign 공통 설정만 빠진다")
    void skipsFeignWithoutFeign() {
        web.withClassLoader(new FilteredClassLoader(RequestInterceptor.class))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(FeignHeaderInterceptor.class)
                        .hasSingleBean(GlobalExceptionHandler.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class ServiceBeans {

        @Bean
        ServiceExceptionHandler serviceExceptionHandler() {
            return new ServiceExceptionHandler();
        }

        @Bean
        ErrorDecoder serviceErrorDecoder() {
            return (methodKey, response) -> new IllegalStateException(methodKey);
        }
    }

    static class ServiceExceptionHandler extends GlobalExceptionHandler {
    }
}
