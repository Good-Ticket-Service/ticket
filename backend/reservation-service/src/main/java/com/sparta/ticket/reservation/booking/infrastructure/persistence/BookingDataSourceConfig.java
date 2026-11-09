package com.sparta.ticket.reservation.booking.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * DB 연결 풀을 둘로 나눈다. 사용자 요청은 기본 풀(booking-main)을, 결제를 끝내는 쪽은 {@link CompletionPool} 안에서
 * 전용 풀(booking-completion)을 쓴다. 두 풀은 같은 DB·같은 계정이고, 어느 풀을 쓸지는 요청 스레드의 표시로만 정한다.
 *
 * Note: Boot가 만들던 DataSource를 대신 만든다. 접속 정보는 테스트 컨테이너가 주는 JdbcConnectionDetails가 있으면 그것을, 없으면
 * spring.datasource.* 를 쓴다. Boot는 DataSource 빈이 따로 있으면 설정 기반 JdbcConnectionDetails를 만들지 않는다.
 * spring.datasource.hikari.* 는 기본 풀에 그대로 바인딩한다. 기본 DataSource가 라우팅 객체라 HikariDataSource로 unwrap하는 코드는
 * 기본 풀을 받는다.
 */
@Configuration
@EnableConfigurationProperties(CompletionPoolProperties.class)
public class BookingDataSourceConfig {

    static final String MAIN = "booking-main";
    static final String COMPLETION = "booking-completion";

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariDataSource bookingMainDataSource(
            DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> connection) {
        return pool(properties, connection.getIfAvailable(), MAIN);
    }

    @Bean
    public HikariDataSource bookingCompletionDataSource(
            DataSourceProperties properties,
            ObjectProvider<JdbcConnectionDetails> connection,
            CompletionPoolProperties completion) {
        HikariDataSource pool = pool(properties, connection.getIfAvailable(), COMPLETION);
        pool.setMaximumPoolSize(completion.maximumPoolSize());
        return pool;
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource bookingMainDataSource, HikariDataSource bookingCompletionDataSource) {
        RoutingDataSource routing = new RoutingDataSource();
        routing.setTargetDataSources(Map.of(MAIN, bookingMainDataSource, COMPLETION, bookingCompletionDataSource));
        routing.setDefaultTargetDataSource(bookingMainDataSource);
        return routing;
    }

    private static HikariDataSource pool(
            DataSourceProperties properties, JdbcConnectionDetails connection, String name) {
        DataSourceBuilder<HikariDataSource> builder = DataSourceBuilder.create().type(HikariDataSource.class);
        if (connection != null) {
            builder.url(connection.getJdbcUrl())
                    .username(connection.getUsername())
                    .password(connection.getPassword())
                    .driverClassName(connection.getDriverClassName());
        } else {
            builder.url(properties.determineUrl())
                    .username(properties.determineUsername())
                    .password(properties.determinePassword())
                    .driverClassName(properties.determineDriverClassName());
        }
        HikariDataSource pool = builder.build();
        pool.setPoolName(name);
        return pool;
    }

    static final class RoutingDataSource extends AbstractRoutingDataSource {

        @Override
        protected Object determineCurrentLookupKey() {
            return CompletionPool.isActive() ? COMPLETION : MAIN;
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return iface.isInstance(this)
                    ? iface.cast(this)
                    : determineTargetDataSource().unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || determineTargetDataSource().isWrapperFor(iface);
        }
    }
}
