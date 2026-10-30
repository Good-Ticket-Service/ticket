package com.sparta.ticket.payment.domain.model.settlement;

/**
 * 결제 1건의 정산 금액. 모두 원 단위 정수이고 만분율 계산은 원 미만을 버린다.
 * 보존식 E + P = N + F + V + Bn + Bv가 항상 성립한다(할인은 플랫폼 부담이라 판매자 몫을 줄이지 않는다).
 *
 * @param gross 정가 합계 G
 * @param discount 플랫폼 부담 할인 P
 * @param bookingFee 예매수수료 Bf(VAT 포함)
 * @param paid 고객 결제액 E = G − P + Bf
 * @param commission 판매대행 수수료 F = floor(G × 수수료율)
 * @param commissionVat 수수료 VAT V = floor(F × VAT율)
 * @param sellerAmount 판매자 몫 N = G − F − V
 * @param bookingFeeNet 예매수수료 순수입 Bn = floor(Bf × 10000 / (10000 + VAT율))
 * @param bookingFeeVat 예매수수료 VAT Bv = Bf − Bn
 */
public record SaleAmounts(
        int gross,
        int discount,
        int bookingFee,
        int paid,
        int commission,
        int commissionVat,
        int sellerAmount,
        int bookingFeeNet,
        int bookingFeeVat) {

    /** 계산 결과. 실패해도 예외를 던지지 않아 호출자가 DB를 바꾸기 전에 분기할 수 있다. */
    public sealed interface Result permits Calculated, Rejected {}

    public record Calculated(SaleAmounts amounts) implements Result {}

    public record Rejected(SettlementFailure reason) implements Result {}

    /**
     * 결제 금액을 정산 금액으로 나눈다. 순수 함수라 DB 상태를 바꾸지 않는다.
     * Note: 모든 중간값을 long으로 계산한다. int 곱셈은 정가가 21만 원만 넘어도 bp를 곱할 때 넘친다.
     */
    public static Result calculate(int gross, int discount, int bookingFee, int paidAmount, SettlementPolicy policy) {
        if (gross <= 0 || discount < 0 || bookingFee < 0) return new Rejected(SettlementFailure.INVALID_INPUT);
        if (discount > gross) return new Rejected(SettlementFailure.DISCOUNT_EXCEEDS_GROSS);
        long paid = (long) gross - discount + bookingFee;
        if (paid != paidAmount) return new Rejected(SettlementFailure.PAID_AMOUNT_MISMATCH);
        long commission = (long) gross * policy.commissionRateBp() / SettlementPolicy.BASIS;
        long commissionVat = commission * policy.vatRateBp() / SettlementPolicy.BASIS;
        long sellerAmount = gross - commission - commissionVat;
        long bookingFeeNet = (long) bookingFee * SettlementPolicy.BASIS / (SettlementPolicy.BASIS + policy.vatRateBp());
        long bookingFeeVat = bookingFee - bookingFeeNet;
        if (sellerAmount < 0 || paid > Integer.MAX_VALUE) return new Rejected(SettlementFailure.OUT_OF_RANGE);
        return new Calculated(new SaleAmounts(
                gross,
                discount,
                bookingFee,
                (int) paid,
                (int) commission,
                (int) commissionVat,
                (int) sellerAmount,
                (int) bookingFeeNet,
                (int) bookingFeeVat));
    }

    public SaleAmounts {
        long left = (long) paid + discount;
        long right = (long) sellerAmount + commission + commissionVat + bookingFeeNet + bookingFeeVat;
        if (left != right) throw new IllegalArgumentException("정산 보존식 E + P = N + F + V + Bn + Bv가 맞지 않는다");
    }
}
