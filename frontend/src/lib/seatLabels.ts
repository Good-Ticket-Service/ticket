/**
 * 예약 응답에 좌석 이름이 없어, 고른 브라우저에 저장해 두고 보여 준다. 키는 예약 ID, REDIS 모드 선점은 holdId다.
 * 다른 기기에서는 없으므로 부르는 쪽이 좌석 수로 대신 표시한다.
 */
const key = (id: number | string) => `ticket.seatLabel.${id}`;

export function saveSeatLabel(id: number | string, label: string): void {
  try {
    localStorage.setItem(key(id), label);
  } catch {
    // 저장하지 못하면 좌석 수로 표시한다.
  }
}

export function seatLabel(id: number | string): string | null {
  try {
    return localStorage.getItem(key(id));
  } catch {
    return null;
  }
}
