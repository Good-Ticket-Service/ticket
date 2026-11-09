package com.sparta.ticket.waitingroom;

import com.sparta.ticket.waitingroom.support.WaitingRoomIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WaitingRoomServiceApplicationTest extends WaitingRoomIntegrationTest {

    @Test
    @DisplayName("DB 없이 Redis만으로 컨텍스트가 뜬다")
    void contextLoads() {}
}
