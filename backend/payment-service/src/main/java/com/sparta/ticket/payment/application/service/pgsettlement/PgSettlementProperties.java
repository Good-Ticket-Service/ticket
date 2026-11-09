package com.sparta.ticket.payment.application.service.pgsettlement;

import com.sparta.ticket.payment.domain.model.pgsettlement.PgFeePolicy;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("payment.pg")
public record PgSettlementProperties(
        List<PgFeePolicy> fees,
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2026-01-01") LocalDate startDate,
        @DefaultValue("2000") int pageSize,
        @DefaultValue("1") int graceBusinessDays) {
    public PgSettlementProperties {
        if (fees == null
                || fees.isEmpty()
                || pageSize < 1
                || pageSize > 5000
                || graceBusinessDays < 0
                || graceBusinessDays > 10
                || startDate == null) throw new IllegalArgumentException("PG 정산 설정이 없다");
        fees = List.copyOf(fees);
        var versions = new HashSet<String>();
        var starts = new HashSet<String>();
        for (var fee : fees) {
            if (!versions.add(fee.provider() + ":" + fee.version())
                    || !starts.add(fee.provider() + ":" + fee.effectiveFrom()))
                throw new IllegalArgumentException("PG 정책 버전·적용 시작이 중복된다");
        }
        for (String provider : List.of("TOSS", "KAKAO"))
            if (fees.stream()
                    .noneMatch(p ->
                            p.provider().equals(provider) && !p.effectiveFrom().isAfter(startDate.atStartOfDay())))
                throw new IllegalArgumentException("정산 시작일에 적용되는 PG 정책이 없다: " + provider);
    }
}
