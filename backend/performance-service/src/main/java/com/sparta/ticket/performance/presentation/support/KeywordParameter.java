package com.sparta.ticket.performance.presentation.support;

/**
 * 공개 목록 검색어. 앞뒤 공백을 지운 뒤 남은 스페이스(U+0020)를 모두 지운다. 남는 것이 없으면 검색어 없음(null)이다(이슈 #442).
 * Note: 띄어쓰기를 무시하고 비교하므로 저장소는 이 값과 공백을 지운 필드를 비교한다. 탭·줄바꿈은 검색어에 들어올 일이 거의 없어
 * 가운데에 있으면 지우지 않는다(이슈 #442 결정).
 */
public final class KeywordParameter {

    private KeywordParameter() {}

    public static String normalize(String value) {
        String stripped = RequestText.stripToNull(value);
        if (stripped == null) {
            return null;
        }
        String withoutSpaces = stripped.replace(" ", "");
        return withoutSpaces.isEmpty() ? null : withoutSpaces;
    }
}
