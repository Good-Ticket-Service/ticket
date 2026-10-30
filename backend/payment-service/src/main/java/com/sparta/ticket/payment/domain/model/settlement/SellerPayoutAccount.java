package com.sparta.ticket.payment.domain.model.settlement;

import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    @Column(length = 10)
    private String bankCode;

    /** 모의 계좌 참조. 은행·계좌번호의 해시라 원문을 되돌릴 수 없다. 등록 전에는 없다. */
    @Column(length = 100)
    private String accountRef;

    /** 계좌번호 끝 4자리만 남긴 값. */
    @Column(length = 40)
    private String accountMasked;

    @Column(length = 100)
    private String holderMasked;

    @Version
    @Column(nullable = false)
    private long version;

    public boolean isRegistered() {
        return accountRef != null;
    }

    /**
     * 계좌를 등록하거나 바꾼다. 계좌번호·예금주 원문은 저장하지 않고 모의 참조와 마스킹 값만 남긴다.
     * Note: 이미 만든 지급의 계좌 스냅샷은 바꾸지 않는다. 지급은 실행권을 얻을 때 이 행의 최신 버전을 스냅샷으로 뜬다.
     */
    public void register(String bankCode, String accountNumber, String holderName) {
        String digits = accountNumber.replaceAll("[^0-9]", "");
        if (digits.length() < 4) throw new IllegalArgumentException("계좌번호는 숫자 4자리 이상이다");
        this.bankCode = bankCode;
        this.accountRef = "acct_" + sha256(bankCode + ":" + digits).substring(0, 32);
        this.accountMasked = "****" + digits.substring(digits.length() - 4);
        this.holderMasked = maskName(holderName.strip());
    }

    /** 홍길동 → 홍*동, 김철 → 김*. */
    static String maskName(String name) {
        if (name.length() <= 1) return "*";
        if (name.length() == 2) return name.charAt(0) + "*";
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
