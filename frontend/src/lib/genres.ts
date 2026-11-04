/** 판매자 등록·관객 검색·공연 표시에서 사용하는 장르의 단일 정의다. */
export const GENRE_LABEL = {
  MUSICAL: '뮤지컬',
  CONCERT: '콘서트',
  PLAY: '연극',
  SPORTS: '스포츠',
  CLASSICAL: '클래식',
  KOREAN_MUSIC: '국악',
  DANCE: '무용',
  CIRCUS_MAGIC: '서커스·마술',
  MULTIDISCIPLINARY: '복합 공연',
} as const;

export type Genre = keyof typeof GENRE_LABEL;
export const GENRE_OPTIONS = Object.entries(GENRE_LABEL).map(([value, label]) => ({ value: value as Genre, label }));

export function genreLabel(code: string): string {
  return GENRE_LABEL[code as Genre] ?? code;
}
