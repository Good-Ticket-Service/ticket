// 공개 공연 목록의 판매 상태 필터(이슈 #382). 서버 status 파라미터와 같은 값을 쓴다.
export type SaleStatus = 'ON_SALE' | 'UPCOMING' | 'ENDED';

export const SALE_STATUS_OPTIONS: { value: SaleStatus; label: string }[] = [
  { value: 'ON_SALE', label: '판매중' },
  { value: 'UPCOMING', label: '판매예정' },
  { value: 'ENDED', label: '판매종료' },
];

const ORDER: SaleStatus[] = ['ON_SALE', 'UPCOMING', 'ENDED'];
export const DEFAULT_SALE_STATUSES: SaleStatus[] = ['ON_SALE', 'UPCOMING'];

/** URL의 status 값(쉼표 구분)을 읽는다. 없거나 알 수 없는 값만 있으면 기본(판매중·판매예정)이다. */
export function parseSaleStatuses(value: string | null): SaleStatus[] {
  const picked = new Set((value ?? '').split(',').map((item) => item.trim()));
  const statuses = ORDER.filter((status) => picked.has(status));
  return statuses.length > 0 ? statuses : [...DEFAULT_SALE_STATUSES];
}

/** 하나를 켜거나 끈다. 마지막 하나는 끌 수 없다(서버는 빈 status를 400으로 거절한다). */
export function toggleSaleStatus(current: SaleStatus[], status: SaleStatus): SaleStatus[] {
  const next = current.includes(status) ? current.filter((item) => item !== status) : [...current, status];
  return next.length > 0 ? ORDER.filter((item) => next.includes(item)) : current;
}

/** 요청·URL에 넣을 값. 기본 선택이면 status를 보내지 않는다(빈 값을 보내지 않기 위해 null). */
export function saleStatusParam(statuses: SaleStatus[]): string | null {
  const isDefault =
    statuses.length === DEFAULT_SALE_STATUSES.length && DEFAULT_SALE_STATUSES.every((item) => statuses.includes(item));
  return isDefault ? null : statuses.join(',');
}

const seoulDate = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul' });

/**
 * 응답만으로 판매종료를 확정할 수 있는지. period.to(마지막 공연 날짜)가 오늘(KST)보다 이전일 때만 참이다.
 * 오늘이거나 period가 없으면 날짜만으로 알 수 없어 표시하지 않는다(이슈 #382 정책 D1).
 */
export function isEndedByPeriod(period: { to: string } | null | undefined, now: number): boolean {
  if (!period?.to) return false;
  return period.to < seoulDate.format(new Date(now));
}
