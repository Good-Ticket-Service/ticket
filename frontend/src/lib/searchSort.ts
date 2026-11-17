/**
 * 검색 결과 정렬(이슈 #442). 관련도순(RELEVANCE)은 검색어가 있을 때만 고를 수 있다.
 * 서버는 검색어 없는 RELEVANCE를 400으로 거절하므로, 검색어가 없으면 보내지 않는다.
 */
export type SearchSort = 'RELEVANCE' | 'LATEST' | 'BOOKING_OPEN_ASC';

const SORT_LABEL: Record<SearchSort, string> = {
  RELEVANCE: '관련도순',
  LATEST: '최근 공개순',
  BOOKING_OPEN_ASC: '예매 오픈순',
};

/**
 * 서버 RequestText.isBlank와 같은 공백 범위: Java Character.isWhitespace(U+0009–U+000D, U+001C–U+001F, 유니코드 공백)
 * 또는 Character.isSpaceChar(Zs·Zl·Zp, NBSP 포함). JS의 trim()·\s와는 범위가 달라 따로 둔다(이슈 #442 리뷰).
 */
const EDGE_BLANKS = /^[\u0009-\u000D\u001C-\u001F\p{Zs}\p{Zl}\p{Zp}]+|[\u0009-\u000D\u001C-\u001F\p{Zs}\p{Zl}\p{Zp}]+$/gu;

/**
 * 서버 KeywordParameter.normalize와 같은 규칙으로 검색어가 있는지 본다.
 * 앞뒤 공백을 지우고 스페이스(U+0020)를 모두 지운 뒤 남는 글자가 있어야 한다.
 */
export function hasSearchKeyword(keyword: string): boolean {
  return keyword.replace(EDGE_BLANKS, '').replace(/ /g, '') !== '';
}

/** URL의 sort를 실제로 보낼 정렬로 바꾼다. 검색어가 있으면 관련도순이 기본이다. */
export function resolveSearchSort(param: string | null, hasKeyword: boolean): SearchSort {
  if (param === 'LATEST' || param === 'BOOKING_OPEN_ASC') return param;
  return hasKeyword ? 'RELEVANCE' : 'LATEST';
}

/**
 * 헤더 검색창으로 새로 검색할 때의 URL 값. 필터는 유지하고, 페이지와 정렬은 지운다.
 * 정렬을 지우면 검색어가 있을 때 관련도순, 없을 때 최신순으로 돌아간다(사용자 결정, 2026-11-17).
 */
export function newSearchParams(current: URLSearchParams, text: string): URLSearchParams {
  const next = new URLSearchParams(current);
  if (text) next.set('keyword', text);
  else next.delete('keyword');
  next.delete('page');
  next.delete('sort');
  return next;
}

/** 화면에 보일 정렬 버튼. 검색어가 없으면 관련도순을 숨긴다. */
export function searchSortOptions(hasKeyword: boolean): { value: SearchSort; label: string }[] {
  const values: SearchSort[] = hasKeyword ? ['RELEVANCE', 'LATEST', 'BOOKING_OPEN_ASC'] : ['LATEST', 'BOOKING_OPEN_ASC'];
  return values.map((value) => ({ value, label: SORT_LABEL[value] }));
}
