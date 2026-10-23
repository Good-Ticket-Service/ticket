package com.sparta.ticket.common.autoconfigure;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 동적 검색 쿼리용 JPAQueryFactory. 서비스의 infrastructure 계층(예: EventQueryRepositoryImpl)에서 주입받아 쓴다.
 * 라이브러리는 관리가 이어지는 OpenFeign 포크(io.github.openfeign.querydsl)를 쓴다.
 * JPA가 설정된 서비스에서만 켜진다. 서비스가 JPAQueryFactory를 직접 정의하면 이 빈은 빠진다. 끄기: {@code common.querydsl.enabled=false}
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass(JPAQueryFactory.class)
@ConditionalOnBean(EntityManagerFactory.class)
@ConditionalOnProperty(prefix = "common.querydsl", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommonQuerydslAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
