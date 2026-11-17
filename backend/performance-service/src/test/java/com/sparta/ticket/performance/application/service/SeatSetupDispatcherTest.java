package com.sparta.ticket.performance.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeatSetupDispatcherTest {

    @Test
    @DisplayName("실행기가 작업을 거절해도 예외를 올리지 않고 다음 회차를 넘긴다(거절된 회차는 재시도 스케줄러가 보낸다)")
    void rejectedScheduleDoesNotStopOthers() {
        SeatSetupProcessor processor = mock(SeatSetupProcessor.class);
        List<Runnable> accepted = new ArrayList<>();
        int[] submitted = {0};
        Executor rejectsSecond = task -> {
            if (++submitted[0] == 2) {
                throw new RejectedExecutionException("가득 참");
            }
            accepted.add(task);
        };
        SeatSetupDispatcher dispatcher = new SeatSetupDispatcher(processor, rejectsSecond);

        assertThatCode(() -> dispatcher.dispatch(List.of(1L, 2L, 3L))).doesNotThrowAnyException();

        accepted.forEach(Runnable::run);
        verify(processor).processSafely(1L);
        verify(processor, never()).processSafely(2L);
        verify(processor).processSafely(3L);
    }
}
