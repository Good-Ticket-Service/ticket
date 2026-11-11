package com.sparta.ticket.payment.infrastructure.messaging;

import com.sparta.ticket.payment.PaymentServiceApplication;
import com.sparta.ticket.payment.application.dto.payment.MessageConfirmCommand;
import com.sparta.ticket.payment.application.service.payment.MessageApprovalTransactions;
import com.sparta.ticket.payment.application.service.payment.PaymentTransactions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 별도 JVM의 실제 TX 경계에서만 멈춘다. 제품 코드에 장애 주입 분기를 넣지 않는다. */
public final class PaymentConfirmProcessMain {
    private PaymentConfirmProcessMain() {}

    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[] {PaymentServiceApplication.class, Checkpoints.class}, args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Checkpoints {
        @Bean
        static BeanPostProcessor checkpoint() {
            String stage = System.getProperty("confirm.checkpoint", "NONE");
            String order = System.getProperty("confirm.checkpoint.order", "");
            long payment = Long.getLong("confirm.checkpoint.payment", -1);
            Path marker = Path.of(System.getProperty("confirm.checkpoint.marker"));
            var hit = new AtomicBoolean();
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof MessageApprovalTransactions) && !(bean instanceof PaymentTransactions))
                        return bean;
                    var proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        String method = invocation.getMethod().getName();
                        Object[] arguments = invocation.getArguments();
                        boolean target = arguments.length > 0
                                && (arguments[0] instanceof MessageConfirmCommand command
                                        ? order.equals(command.orderId())
                                        : Long.valueOf(payment).equals(arguments[0]));
                        boolean beforeResult = stage.equals("PG") && method.equals("complete");
                        if (target && beforeResult && hit.compareAndSet(false, true)) block(marker, stage);
                        Object result = invocation.proceed();
                        boolean after = stage.equals("REGISTER") && method.equals("register")
                                || stage.equals("CLAIM") && method.equals("claim")
                                || stage.equals("RESULT") && method.equals("confirmRecovery");
                        if (target && after && hit.compareAndSet(false, true)) block(marker, stage);
                        return result;
                    });
                    return proxy.getProxy();
                }
            };
        }

        private static void block(Path marker, String stage) throws Exception {
            Files.writeString(marker, stage);
            new CountDownLatch(1).await();
        }
    }
}
