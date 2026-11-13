import type { SeatInput } from '../api/manageTypes';
export interface VenueRecord {
  id: number;
  name: string;
  region: string;
  address?: string;
  version: number;
  versionKnown?: boolean;
  grades: { id: number; name: string }[];
  seatCount: number | null;
}
export const VENUE_RECORDS = 'ticket.admin.venues';
export function venueVersion(record: VenueRecord | undefined, value: string) {
  if (value !== '') {
    const version = Number(value);
    return /^\d+$/.test(value) && Number.isSafeInteger(version) ? version : null;
  }
  return record &&
    record.versionKnown !== false &&
    Number.isSafeInteger(record.version) &&
    record.version >= 0
    ? record.version
    : null;
}
export function seatDraft(
  section: string,
  rowsInput: string,
  perRowInput: string,
  gradeId: number,
): { rows: string[]; seats: SeatInput[]; error: string | null; total: number } {
  const fail = (error: string) => ({ rows: [], seats: [], error, total: 0 });
  const perRow = Number(perRowInput);
  if (!section.trim() || section.trim().length > 20)
    return fail('구역은 공백을 제외한 1~20자로 입력해 주세요.');
  if (!/^\d+$/.test(perRowInput) || !Number.isSafeInteger(perRow) || perRow < 1 || perRow > 5000)
    return fail('열마다 좌석 수는 1~5,000 사이의 정수로 입력해 주세요.');
  let rows: string[];
  const range = /^\s*(\d+|[A-Za-z])\s*-\s*(\d+|[A-Za-z])\s*$/.exec(rowsInput);
  if (range) {
    const numeric = /^\d+$/.test(range[1]) && /^\d+$/.test(range[2]);
    const letters =
      (/^[A-Z]$/.test(range[1]) && /^[A-Z]$/.test(range[2])) ||
      (/^[a-z]$/.test(range[1]) && /^[a-z]$/.test(range[2]));
    if (!numeric && !letters) return fail('열 범위는 1-3 또는 A-C처럼 같은 형식으로 입력해 주세요.');
    const from = numeric ? Number(range[1]) : range[1].charCodeAt(0),
      to = numeric ? Number(range[2]) : range[2].charCodeAt(0);
    if (
      !Number.isSafeInteger(from) ||
      !Number.isSafeInteger(to) ||
      to < from ||
      (to - from + 1) * perRow > 5000
    )
      return fail('열 범위를 확인해 주세요. 요청당 좌석은 최대 5,000개입니다.');
    rows = Array.from({ length: to - from + 1 }, (_, i) =>
      numeric ? String(from + i) : String.fromCharCode(from + i),
    );
  } else rows = rowsInput.split(',').map((row) => row.trim());
  if (rows.length * perRow > 5000) return fail('요청당 좌석은 최대 5,000개입니다.');
  if (rows.some((row) => !row || row.length > 10)) return fail('열 이름은 1~10자로 입력해 주세요.');
  if (new Set(rows).size !== rows.length) return fail('같은 열 이름을 중복 입력할 수 없어요.');
  if (!Number.isSafeInteger(gradeId) || gradeId <= 0)
    return fail('좌석에 적용할 등급 ID를 선택하거나 입력해 주세요.');
  return {
    rows,
    seats: rows.flatMap((rowLabel) =>
      Array.from({ length: perRow }, (_, i) => ({
        section: section.trim(),
        rowLabel,
        seatNumber: i + 1,
        venueSeatGradeId: gradeId,
      })),
    ),
    error: null,
    total: rows.length * perRow,
  };
}
export function roomScopes(input: string) {
  const ids = input.split(/[\s,]+/).filter(Boolean);
  if (
    !ids.length ||
    ids.length > 100 ||
    ids.some((id) => !/^[1-9]\d*$/.test(id) || !Number.isSafeInteger(Number(id)))
  )
    return null;
  return [...new Set(ids.map((id) => `schedule:${id}`))];
}
export function roomTimeError(open: string, close: string, now = Date.now()) {
  const start = Date.parse(open + '+09:00'),
    end = Date.parse(close + '+09:00');
  if (!Number.isFinite(start) || !Number.isFinite(end)) return '오픈·종료 시각을 입력해 주세요.';
  if (start < now + 60000) return '대기열 오픈은 현재 시각보다 1분 이상 뒤여야 해요.';
  return end > start ? null : '종료 시각은 오픈 시각보다 뒤여야 해요.';
}
