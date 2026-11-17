package com.sparta.ticket.performance.domain.venue;

import java.util.List;
import java.util.Optional;

/**
 * 공연장 지역 코드. 검색 별칭에는 코드 자체를 넣지 않는다(이슈 #438).
 * Note: 코드·별칭 변경 시 migrations/438-region-codes.sql과 script/kopis-seed/generate.py의 REGION_ALIASES도 함께 갱신한다.
 * 이행·적재는 이 검색 별칭에 코드 자체를 추가로 허용한다.
 */
public enum Region {
    SEOUL("서울", "서울특별시"),
    GYEONGGI("경기", "경기도"),
    INCHEON("인천", "인천광역시"),
    GANGWON("강원", "강원특별자치도", "강원도"),
    CHUNGBUK("충북", "충청북도"),
    CHUNGNAM("충남", "충청남도"),
    DAEJEON("대전", "대전광역시"),
    SEJONG("세종", "세종특별자치시"),
    JEONBUK("전북", "전북특별자치도", "전라북도"),
    JEONNAM_GWANGJU("전남광주", "광주", "전남", "전남광주통합특별시", "광주광역시", "전라남도"),
    GYEONGBUK("경북", "경상북도"),
    GYEONGNAM("경남", "경상남도"),
    DAEGU("대구", "대구광역시"),
    ULSAN("울산", "울산광역시"),
    BUSAN("부산", "부산광역시"),
    JEJU("제주", "제주특별자치도", "제주도");

    private final List<String> aliases;

    Region(String... aliases) {
        this.aliases = List.of(aliases);
    }

    /** 저장·응답에 쓸 수 있는 정확한 코드인지 확인한다. 예전 한글 값은 코드가 아니다. */
    public static boolean isCode(String value) {
        if (value == null) {
            return false;
        }
        try {
            valueOf(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 앞뒤 공백 정리는 요청 경계에서 수행한다. 지역 이름 전체가 같을 때만 지역 검색으로 확장한다. */
    public static Optional<Region> fromKeyword(String keyword) {
        for (Region region : values()) {
            if (region.aliases.contains(keyword)) {
                return Optional.of(region);
            }
        }
        return Optional.empty();
    }
}
