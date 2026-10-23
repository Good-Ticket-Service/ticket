package com.sparta.ticket.common.autoconfigure;

import com.sparta.ticket.common.config.HeaderAuditorAware;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * BaseEntity의 Audit 컬럼을 채우는 JPA Auditing. JPA가 설정된 서비스에서만 켜진다.
 *
 * Note: 끄면({@code common.jpa-auditing.enabled=false}) created_at 등 NOT NULL 컬럼이 비어 저장이 실패한다.
 * 끄는 서비스는 @EnableJpaAuditing을 직접 선언해야 한다. 켜 둔 채로 서비스가 @EnableJpaAuditing을 또 선언하면
 * 같은 이름의 빈이 두 번 등록돼 기동에 실패한다. 기록할 사용자만 바꾸려면 이름이 auditorAware인 빈을 정의한다.
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass(EnableJpaAuditing.class)
@ConditionalOnBean(EntityManagerFactory.class)
@ConditionalOnProperty(prefix = "common.jpa-auditing", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class CommonJpaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "auditorAware")
    public AuditorAware<String> auditorAware() {
        return new HeaderAuditorAware();
    }
}
