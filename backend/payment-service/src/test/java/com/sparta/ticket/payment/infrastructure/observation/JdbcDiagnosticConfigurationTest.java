package com.sparta.ticket.payment.infrastructure.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JdbcDiagnosticConfigurationTest {
    @Test
    void doesNotWrapConnectionsOutsideSampledRequest() throws Exception {
        DataSource original = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(original.getConnection()).thenReturn(connection);
        assertThat(JdbcDiagnosticConfiguration.dataSource(original).getConnection())
                .isSameAs(connection);
    }

    @Test
    void recordsCommitAndSqlWithoutValuesAndPreservesJdbcException() throws Exception {
        DataSource original = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(original.getConnection()).thenReturn(connection);
        String sql = "select 'literal-secret' where id = ?";
        when(connection.prepareStatement(sql)).thenReturn(statement);
        SQLException failure = new SQLException("driver-secret");
        when(statement.execute()).thenThrow(failure);
        DataSource wrapped = JdbcDiagnosticConfiguration.dataSource(original);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/payments");
        request.addHeader("X-P13-Trace", "sample-1");
        request.addHeader("X-P13-Provider", "TOSS");
        Logger logger = (Logger) LoggerFactory.getLogger(JdbcDiagnosticConfiguration.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new JdbcDiagnosticConfiguration()
                    .jdbcDiagnosticFilter()
                    .doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                        try (Connection acquired = wrapped.getConnection()) {
                            PreparedStatement prepared = acquired.prepareStatement(sql);
                            prepared.setString(1, "binding-secret");
                            assertThatThrownBy(prepared::execute).isSameAs(failure);
                            acquired.commit();
                        } catch (SQLException error) {
                            throw new IllegalStateException(error);
                        }
                    });
            String logged = appender.list.get(0).getFormattedMessage();
            assertThat(logged).contains("acquire", "execute", "commit", "hold", "sample-1");
            assertThat(logged).doesNotContain("literal-secret", "binding-secret", "driver-secret");
            verify(statement).setString(1, "binding-secret");
            verify(connection).commit();
            verify(connection).close();
            assertThat(wrapped.getConnection()).isSameAs(connection);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
