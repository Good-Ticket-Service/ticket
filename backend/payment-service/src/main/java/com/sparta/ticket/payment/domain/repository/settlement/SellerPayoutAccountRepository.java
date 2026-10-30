package com.sparta.ticket.payment.domain.repository.settlement;

import com.sparta.ticket.payment.domain.model.settlement.SellerPayoutAccount;
import java.util.Optional;

/** 판매자 수취 계좌. 판매자마다 한 행이고, 바꿀 때는 이 행만 잠근다. */
public interface SellerPayoutAccountRepository {

    Optional<SellerPayoutAccount> findBySellerId(Long sellerId);

    Optional<SellerPayoutAccount> lockBySellerId(Long sellerId);

    SellerPayoutAccount saveAndFlush(SellerPayoutAccount account);
}
