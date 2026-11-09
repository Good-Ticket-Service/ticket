package com.sparta.ticket.payment.application.port.pgsettlement;

import com.sparta.ticket.payment.domain.model.pgsettlement.PgSettlement;
import java.time.LocalDate;

public interface PgSettlementClient {
    PgSettlement.Page read(String provider, LocalDate date, String cursor, int size, String snapshotId);
}
