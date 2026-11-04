/** 서버 시각은 오프셋이 있거나 없다. 오프셋이 없으면 업무 기준인 서울 시각으로 읽는다. 날짜만 오면 그날 0시로 읽는다. */
export function parseTime(value: string): Date {
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return new Date(`${value}T00:00:00+09:00`);
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

/** datetime-local 입력값(서울 시각)을 오프셋이 있는 ISO 8601로 바꾼다. */
export function seoulInputToOffset(value: string): string {
  return `${value.length === 16 ? `${value}:00` : value}+09:00`;
}

/** datetime-local 입력값(서울 시각)을 오프셋 없는 서울 시각으로 바꾼다. LocalDateTime을 받는 API에 쓴다. */
export function seoulInputToLocal(value: string): string {
  return value.length === 16 ? `${value}:00` : value;
}

/** 서버 시각을 datetime-local 입력값(서울 시각)으로 바꾼다. 기본값은 지금부터 minutes분 뒤다. */
export function toSeoulInput(value?: string, minutes = 0): string {
  const base = value ? parseTime(value) : new Date(Date.now() + minutes * 60 * 1000);
  return new Date(base.getTime() + 9 * 60 * 60 * 1000).toISOString().slice(0, 16);
}
