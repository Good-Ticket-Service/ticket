package com.sparta.ticket.fakepg.presentation.controller.settlement;

import com.sparta.ticket.fakepg.application.service.settlement.FakeSettlementService;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fake/settlements")
@RequiredArgsConstructor
public class FakeSettlementController {
    private final FakeSettlementService settlements;

    @GetMapping
    public FakeSettlementService.Page read(
            @RequestParam PgProvider provider,
            @RequestParam LocalDate date,
            @RequestParam(defaultValue = "") String cursor,
            @RequestParam(defaultValue = "2000") int size,
            @RequestParam(defaultValue = "") String snapshotId) {
        return settlements.read(provider, date, cursor, size, snapshotId);
    }
}
