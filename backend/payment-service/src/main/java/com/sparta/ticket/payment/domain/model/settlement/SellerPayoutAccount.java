package com.sparta.ticket.payment.domain.model.settlement;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 판매자별 정산 기준 행이자 수취 계좌(settlement-design 3절 8번). 정산서 발행은 이 행을 잠가 판매자 단위로 직렬화한다.
 * 발행이 먼저 만들 수 있어 계좌 정보는 비어 있을 수 있다. 행이 있다고 계좌가 등록된 것은 아니다.
 */
@Getter
@Entity
@Table(name = "p_seller_payout_accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerPayoutAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;

    /** 모의 계좌 참조. 등록 전에는 없다. */
    @Column(length = 100)
    private String accountRef;

    @Column(length = 100)
    private String holderMasked;

    @Version
    @Column(nullable = false)
    private long version;
}
