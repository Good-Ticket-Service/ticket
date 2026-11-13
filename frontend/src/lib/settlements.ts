import type { StatementStatus } from '../api/manageTypes';
export const STATEMENT_STATUS_LABEL: Record<StatementStatus, string> = {
  ISSUED: '발행',
  NO_PAYOUT: '지급 없음',
  PAID: '지급 완료',
};
export function settlementRange(now = Date.now()) {
  const day = new Date(now + 9 * 3600000).toISOString().slice(0, 10);
  return {
    from: new Date(Date.parse(day + 'T00:00:00Z') - 6 * 86400000).toISOString().slice(0, 10),
    to: day,
  };
}
function dateValue(value: string) {
  const time = Date.parse(value + 'T00:00:00Z');
  return /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    Number.isFinite(time) &&
    new Date(time).toISOString().slice(0, 10) === value
    ? time
    : NaN;
}
export function settlementPeriodError(from: string, to: string, preview = false): string | null {
  if (preview && (!from || !to)) return '조회 시작일과 종료일을 입력해 주세요.';
  if ((from && !Number.isFinite(dateValue(from))) || (to && !Number.isFinite(dateValue(to))))
    return '올바른 날짜를 입력해 주세요.';
  if (from && to && from > to) return '시작일은 종료일 이전 또는 같은 날짜여야 해요.';
  if (preview && dateValue(to) - dateValue(from) >= 31 * 86400000)
    return '예상 정산은 한 번에 최대 31일까지 조회할 수 있어요.';
  return null;
}
export function settlementPage(value: string | null) {
  const page = Number(value ?? 0);
  return Number.isInteger(page) && page >= 0 && page <= 10000 ? page : 0;
}
export const validScheduleFilter = (value: string) =>
  !value || (/^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)));
