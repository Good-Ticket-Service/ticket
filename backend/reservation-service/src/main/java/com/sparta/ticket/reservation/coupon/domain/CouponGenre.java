package com.sparta.ticket.reservation.coupon.domain;

/**
 * 쿠폰을 쓸 수 있는 공연 장르(#389). 이름은 공연 서비스의 Genre와 같다.
 * 예매가 넘겨 주는 예약의 장르(Genre 이름 문자열)와 이름으로 비교한다(계약 #383).
 * Note: 공연 서비스에 장르가 새로 생기면 여기와 p_coupons의 ck_p_coupons_genre도 함께 늘린다.
 * 늘리기 전에는 새 장르 공연에 이 장르를 지정한 쿠폰을 만들 수 없을 뿐, 전체 장르 쿠폰은 그대로 쓸 수 있다.
 */
public enum CouponGenre {
    CONCERT("콘서트"),
    MUSICAL("뮤지컬"),
    PLAY("연극"),
    SPORTS("스포츠"),
    CLASSICAL("클래식"),
    KOREAN_MUSIC("국악"),
    DANCE("무용"),
    CIRCUS_MAGIC("서커스/마술"),
    MULTIDISCIPLINARY("복합");

    /** 쿠폰을 쓸 수 없을 때 오류 메시지에 넣는 한글 이름. */
    private final String label;

    CouponGenre(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
