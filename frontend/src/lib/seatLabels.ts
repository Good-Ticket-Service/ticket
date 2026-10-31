/**
 * 예약 응답에 좌석 이름이 없어, 고른 브라우저에 저장해 두고 보여 준다.
 * 다른 기기에서는 없으므로 부르는 쪽이 좌석 수로 대신 표시한다.
 */
const key = (reservationId: number) => `ticket.seatLabel.${reservationId}`;

export function saveSeatLabel(reservationId: number, label: string): void {
  try {
    localStorage.setItem(key(reservationId), label);
  } catch {
    // 저장하지 못하면 좌석 수로 표시한다.
  }
}

export function seatLabel(reservationId: number): string | null {
  try {
    return localStorage.getItem(key(reservationId));
  } catch {
    return null;
  }
}
