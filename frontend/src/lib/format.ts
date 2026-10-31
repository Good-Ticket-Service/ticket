/** 서버 시각은 오프셋이 있거나 없다. 오프셋이 없으면 업무 기준인 서울 시각으로 읽는다. */
export function parseTime(value: string): Date {
  return new Date(/(Z|[+-]\d{2}:?\d{2})$/.test(value) ? value : `${value}+09:00`);
}

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

function seoulParts(value: string) {
  // 표시도 브라우저 시간대와 상관없이 서울 기준으로 맞춘다.
  const shifted = new Date(parseTime(value).getTime() + 9 * 60 * 60 * 1000);
  return {
    year: shifted.getUTCFullYear(),
    month: String(shifted.getUTCMonth() + 1).padStart(2, '0'),
    day: String(shifted.getUTCDate()).padStart(2, '0'),
    weekday: WEEKDAYS[shifted.getUTCDay()],
    hour: String(shifted.getUTCHours()).padStart(2, '0'),
    minute: String(shifted.getUTCMinutes()).padStart(2, '0'),
  };
}

export function formatDateTime(value: string): string {
  const p = seoulParts(value);
  return `${p.year}.${p.month}.${p.day} (${p.weekday}) ${p.hour}:${p.minute}`;
}

export function formatDate(value: string): string {
  const p = seoulParts(value);
  return `${p.year}.${p.month}.${p.day}`;
}

export function won(amount: number | null | undefined): string {
  return `${(amount ?? 0).toLocaleString('ko-KR')}원`;
}

export function clock(seconds: number): string {
  const safe = Math.max(0, seconds);
  return `${String(Math.floor(safe / 60)).padStart(2, '0')}:${String(safe % 60).padStart(2, '0')}`;
}
