/**
 * 대기열을 통과하면 받는 입장 토큰. 공연마다 하나이고, Gateway가 좌석 조회·선점에서 X-Queue-Token으로 검사한다.
 * 토큰이 로그인한 회원에게 묶여 있어 탭을 닫거나 로그아웃하면 버린다.
 */
const PREFIX = 'ticket.entryToken.';

interface Entry {
  token: string;
  expiresAt: string;
}

export function saveEntryToken(performanceId: string | number, token: string, expiresAt: string): void {
  try {
    sessionStorage.setItem(PREFIX + performanceId, JSON.stringify({ token, expiresAt } satisfies Entry));
  } catch {
    // 저장하지 못하면 좌석 화면에서 403을 받고 대기열로 돌아간다.
  }
}

/** 만료 전 토큰만 돌려준다. 만료된 토큰을 보내면 Gateway가 어차피 거절하므로 미리 버린다. */
export function entryToken(performanceId: string | number): string | null {
  try {
    const raw = sessionStorage.getItem(PREFIX + performanceId);
    if (!raw) return null;
    const entry = JSON.parse(raw) as Entry;
    if (Date.parse(entry.expiresAt) > Date.now()) return entry.token;
    sessionStorage.removeItem(PREFIX + performanceId);
    return null;
  } catch {
    return null;
  }
}

export function clearEntryToken(performanceId: string | number): void {
  try {
    sessionStorage.removeItem(PREFIX + performanceId);
  } catch {
    // 지우지 못해도 만료 시각이 지나면 쓰지 않는다.
  }
}

export function clearEntryTokens(): void {
  try {
    Object.keys(sessionStorage)
      .filter((key) => key.startsWith(PREFIX))
      .forEach((key) => sessionStorage.removeItem(key));
  } catch {
    // 지우지 못해도 다른 회원의 토큰은 Gateway가 거절한다.
  }
}
