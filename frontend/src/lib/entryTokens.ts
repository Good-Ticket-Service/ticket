import type { WaitingRoom } from '../api/types';
import { parseTime } from './format';

const PREFIX = 'ticket.entryToken.';
const ROOM_PREFIX = `${PREFIX}v2.room.`;
interface Entry {
  token: string;
  expiresAt: string;
  scopes: string[];
}

/** 서버 room의 scope를 함께 저장한다. 토큰 서명·회원·실제 접근 권한은 Gateway가 검증한다. */
export function saveEntryToken(
  room: Pick<WaitingRoom, 'roomId' | 'scopes'>,
  token: string,
  expiresAt: string,
): void {
  try {
    sessionStorage.setItem(
      ROOM_PREFIX + room.roomId,
      JSON.stringify({ token, expiresAt, scopes: room.scopes } satisfies Entry),
    );
  } catch {
    /* 저장 실패 시 Gateway 응답으로 재진입한다. */
  }
}

export function entryToken(scheduleId: string | number): string | null {
  try {
    for (const key of Object.keys(sessionStorage).filter((value) => value.startsWith(ROOM_PREFIX))) {
      try {
        const entry = JSON.parse(sessionStorage.getItem(key) ?? 'null') as Entry | null;
        if (
          !entry ||
          !Array.isArray(entry.scopes) ||
          !Number.isFinite(parseTime(entry.expiresAt).getTime()) ||
          parseTime(entry.expiresAt).getTime() <= Date.now()
        ) {
          sessionStorage.removeItem(key);
          continue;
        }
        if (entry.scopes.includes(`schedule:${scheduleId}`) && typeof entry.token === 'string')
          return entry.token;
      } catch {
        sessionStorage.removeItem(key);
      }
    }
  } catch {
    /* 저장소가 차단되면 토큰 없이 접근하고 Gateway 결과를 따른다. */
  }
  return null;
}

/** 잘못된 토큰은 같은 room의 다른 scope에도 재사용하지 않는다. */
export function clearEntryToken(scheduleId: string | number): void {
  try {
    Object.keys(sessionStorage)
      .filter((key) => key.startsWith(ROOM_PREFIX))
      .forEach((key) => {
        try {
          const entry = JSON.parse(sessionStorage.getItem(key) ?? 'null') as Entry | null;
          if (!entry || entry.scopes.includes(`schedule:${scheduleId}`)) sessionStorage.removeItem(key);
        } catch {
          sessionStorage.removeItem(key);
        }
      });
  } catch {
    /* Gateway가 만료·권한을 최종 검사한다. */
  }
}

export function clearEntryTokens(): void {
  try {
    Object.keys(sessionStorage)
      .filter((key) => key.startsWith(PREFIX))
      .forEach((key) => sessionStorage.removeItem(key));
  } catch {
    /* 저장소가 차단돼도 서버 검사는 유지된다. */
  }
}
