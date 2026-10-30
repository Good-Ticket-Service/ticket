package com.sparta.ticket.payment.domain.model.settlement;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 정산 정책 버전 목록. 적용 시작 시각 순으로 이어지고, 어떤 시각에는 그 시각 이전에 시작한 가장 최근 버전이 적용된다.
 * 설정 오류(버전·시작 시각 중복, 빈 목록)는 기동할 때 이 생성자에서 실패시킨다. 승인 중 설정 오류로 결제가 막히지 않게 하기 위해서다.
 */
public final class SettlementPolicyBook {

    private final List<SettlementPolicy> policies;

    public SettlementPolicyBook(List<SettlementPolicy> policies) {
        if (policies == null || policies.isEmpty()) throw new IllegalArgumentException("정산 정책 버전이 하나도 없다");
        Set<String> versions = new HashSet<>();
        Set<LocalDateTime> starts = new HashSet<>();
        for (SettlementPolicy policy : policies) {
            if (!versions.add(policy.version()))
                throw new IllegalArgumentException("정산 정책 버전이 중복된다: " + policy.version());
            if (!starts.add(policy.effectiveFrom()))
                throw new IllegalArgumentException("정산 정책 적용 시작 시각이 중복된다: " + policy.effectiveFrom());
        }
        this.policies = policies.stream()
                .sorted(Comparator.comparing(SettlementPolicy::effectiveFrom))
                .toList();
    }

    /** at에 적용되는 버전. 첫 버전보다 이른 시각이면 비어 있다(정산 계산 실패로 드러낸다). */
    public Optional<SettlementPolicy> at(LocalDateTime at) {
        SettlementPolicy selected = null;
        for (SettlementPolicy policy : policies) {
            if (policy.effectiveFrom().isAfter(at)) break;
            selected = policy;
        }
        return Optional.ofNullable(selected);
    }

    /** 정산 항목에 고정한 버전. 과거 버전도 지우지 않고 남긴다. */
    public Optional<SettlementPolicy> byVersion(String version) {
        return policies.stream().filter(p -> p.version().equals(version)).findFirst();
    }
}
