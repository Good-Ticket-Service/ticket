import { HOLD_ID } from './holdCheckout';

/**
 * 이 탭에서 잡은 REDIS 모드 선점(회차별 holdId). REDIS 모드 선점은 좌석 선택 완료 전까지 예약이 아니라 내 예매에 없어, 결제 화면을
 * 벗어나면 돌아갈 길이 없다. 좌석 화면이 이 값으로 선점을 다시 조회해 결제 화면으로 돌려보낸다.
 * holdId만으로는 아무것도 할 수 없다(조회·해제·좌석 선택 완료 모두 본인 로그인 확인). 다른 탭·기기에서 잡은 선점은 찾지 못한다.
 */
const PREFIX = 'ticket.hold.';
const key = (scheduleId: string | number) => `${PREFIX}${scheduleId}`;

export function rememberHold(scheduleId: string | number, holdId: string): void {
  try {
    sessionStorage.setItem(key(scheduleId), holdId);
  } catch {
    /* 저장하지 못하면 선점으로 돌아가는 안내만 빠진다. */
  }
}

export function rememberedHold(scheduleId: string | number): string | null {
  try {
    const value = sessionStorage.getItem(key(scheduleId));
    return value && HOLD_ID.test(value) ? value : null;
  } catch {
    return null;
  }
}

/** holdId를 주면 저장된 값이 그 선점일 때만 지운다. 다른 화면이 그사이 새로 잡은 선점을 지우지 않기 위해서다. */
export function forgetHold(scheduleId: string | number, holdId?: string): void {
  try {
    if (holdId === undefined || sessionStorage.getItem(key(scheduleId)) === holdId)
      sessionStorage.removeItem(key(scheduleId));
  } catch {
    /* 지우지 못해도 다음 조회가 404로 지운다. */
  }
}

/** 로그인 회원이 바뀌면 모두 지운다. */
export function forgetAllHolds(): void {
  try {
    Object.keys(sessionStorage)
      .filter((value) => value.startsWith(PREFIX))
      .forEach((value) => sessionStorage.removeItem(value));
  } catch {
    /* 저장소를 쓸 수 없으면 지울 것도 없다. */
  }
}
