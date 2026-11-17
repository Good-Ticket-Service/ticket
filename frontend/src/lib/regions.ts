/**
 * 공연장 지역 코드와 화면 이름의 단일 정의다(이슈 #438).
 * 서버 Region enum의 16개 코드와 첫 번째 이름(짧은 이름)을 그대로 따른다.
 */
export const REGION_LABEL = {
  SEOUL: '서울',
  GYEONGGI: '경기',
  INCHEON: '인천',
  GANGWON: '강원',
  CHUNGBUK: '충북',
  CHUNGNAM: '충남',
  DAEJEON: '대전',
  SEJONG: '세종',
  JEONBUK: '전북',
  JEONNAM_GWANGJU: '전남광주',
  GYEONGBUK: '경북',
  GYEONGNAM: '경남',
  DAEGU: '대구',
  ULSAN: '울산',
  BUSAN: '부산',
  JEJU: '제주',
} as const;

export type Region = keyof typeof REGION_LABEL;
export const REGION_OPTIONS = Object.entries(REGION_LABEL).map(([value, label]) => ({
  value: value as Region,
  label,
}));

/** 모르는 값(예: 브라우저에 남은 예전 한글 기록)은 그대로 보여 준다. */
export function regionLabel(code: string): string {
  return REGION_LABEL[code as Region] ?? code;
}
