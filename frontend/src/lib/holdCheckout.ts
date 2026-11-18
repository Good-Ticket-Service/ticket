import type { Hold } from '../api/types';

/** holdId 형식(docs/api.md). 다르면 서버가 404 HOLD_NOT_FOUND라 요청하지 않는다. */
export const HOLD_ID = /^h_[0-9a-f]{32}$/;

/** 좌석 선택 완료가 실패했을 때 좌석을 다시 골라야 하는 오류. */
export const RESELECT_CODES = new Set([
  'HOLD_EXPIRED',
  'HOLD_NOT_FOUND',
  'SEAT_ALREADY_TAKEN',
  'SEAT_NOT_SELLABLE',
  'SEAT_CONFIG_VERSION_MISMATCH',
]);

/** 선점 응답에 맞는 결제 화면. REDIS 모드는 holdId로, DB 모드는 예약 ID로 간다. */
export function checkoutPath(scheduleId: number | string, hold: Hold): string {
  if (hold.holdId) return `/seats/schedules/${scheduleId}/holds/${hold.holdId}/checkout`;
  return `/reservations/${hold.reservationId}/checkout`;
}

/**
 * 결제에 쓸 예약 ID를 돌려준다. REDIS 모드 선점은 처음 부를 때 좌석 선택 완료로 예약을 만들고, 이후에는 그 예약 ID를 쓴다.
 * 실패하면 다음 호출에서 다시 부른다. 좌석 선택 완료는 같은 선점이면 같은 예약을 돌려줘 다시 불러도 예약이 늘지 않는다.
 */
export function reservationResolver(
  known: number | null,
  convert: () => Promise<Hold>,
): () => Promise<number> {
  let pending: Promise<number> | null = known === null ? null : Promise.resolve(known);
  return () => {
    if (!pending) {
      pending = convert()
        .then((result) => {
          if (result.reservationId === null) throw new Error('좌석 선택 완료 응답에 예약 ID가 없어요.');
          return result.reservationId;
        })
        .catch((error: unknown) => {
          pending = null;
          throw error;
        });
    }
    return pending;
  };
}
