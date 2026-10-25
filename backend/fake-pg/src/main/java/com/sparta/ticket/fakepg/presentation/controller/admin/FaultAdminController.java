package com.sparta.ticket.fakepg.presentation.controller.admin;

import com.sparta.ticket.fakepg.application.service.fault.FaultInjector;
import com.sparta.ticket.fakepg.domain.model.fault.FaultConfig;
import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.presentation.dto.admin.FaultConfigRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장애 설정 관리 API. 실험·테스트가 PG API(제공자 × 동작)마다 장애를 걸고 푼다. 설정은 즉시 반영된다.
 * 예: {@code PUT /fake/admin/faults/TOSS/CONFIRM {"responseLossRate": 0.1}}
 */
@RestController
@RequestMapping("/fake/admin/faults")
@RequiredArgsConstructor
public class FaultAdminController {

    private final FaultInjector faults;

    @GetMapping
    public Map<String, FaultConfig> all() {
        return faults.all();
    }

    @GetMapping("/{provider}/{operation}")
    public FaultConfig get(@PathVariable PgProvider provider, @PathVariable PgOperation operation) {
        return faults.get(provider, operation);
    }

    @PutMapping("/{provider}/{operation}")
    public FaultConfig set(
            @PathVariable PgProvider provider,
            @PathVariable PgOperation operation,
            @RequestBody FaultConfigRequest request) {
        FaultConfig config = request.toConfig();
        faults.set(provider, operation, config);
        return config;
    }

    /** 모든 장애를 푼다. */
    @DeleteMapping
    public ResponseEntity<Void> reset() {
        faults.reset();
        return ResponseEntity.noContent().build();
    }
}
