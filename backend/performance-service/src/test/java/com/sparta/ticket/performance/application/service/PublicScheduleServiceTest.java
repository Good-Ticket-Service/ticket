package com.sparta.ticket.performance.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sparta.ticket.performance.application.dto.PublicScheduleList;
import com.sparta.ticket.performance.application.port.PublicScheduleListCache;
import com.sparta.ticket.performance.application.port.PublicScheduleReader;
import com.sparta.ticket.performance.domain.performance.OperationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class PublicScheduleServiceTest {
    @Test
    @DisplayName("첫 미적중 직후 다른 로더가 값을 채웠으면 새 로더는 재확인으로 DB와 트랜잭션을 건너뛴다")
    void rechecksBeforeDatabaseLoad() {
        PublicScheduleReader reader = mock(PublicScheduleReader.class);
        PublicScheduleListCache cache = mock(PublicScheduleListCache.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        PublicScheduleList data = new PublicScheduleList(OperationStatus.ACTIVE, List.of());
        when(cache.find(10L)).thenReturn(Optional.empty(), Optional.of(data));
        PublicScheduleService service =
                new PublicScheduleService(reader, cache, transactions, new SimpleMeterRegistry(), Clock.systemUTC());

        assertThat(service.getSchedules(10L)).isEmpty();
        verifyNoInteractions(reader, transactions);
        verify(cache, never()).put(10L, data);
    }
}
