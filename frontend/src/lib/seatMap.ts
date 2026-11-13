import type { Seat, SeatLayout, SeatStatus } from '../api/types';

export function mergeSeatStatus(layout: SeatLayout, status: SeatStatus): Seat[] {
  if (
    layout.scheduleId !== status.scheduleId ||
    layout.configVersion !== status.configVersion ||
    layout.seats.length !== status.seatCount
  )
    throw new Error('좌석 구성 버전이 바뀌었어요. 다시 불러와 주세요.');
  const bytes = Uint8Array.from(atob(status.selectableBitmap), (value) => value.charCodeAt(0));
  if (bytes.length !== Math.ceil(status.seatCount / 8))
    throw new Error('좌석 상태를 확인하지 못했어요. 다시 불러와 주세요.');
  return layout.seats.map((seat, index) => ({
    ...seat,
    selectable: (bytes[Math.floor(index / 8)] & (1 << index % 8)) !== 0,
  }));
}

export function seatName(seat: Seat): string {
  return `${seat.gradeName} · ${seat.section} ${seat.rowLabel}열 ${seat.seatNumber}번`;
}

export function seatSections(seats: Seat[]) {
  const sections = new Map<string, Map<string, Seat[]>>();
  for (const seat of seats) {
    const rows = sections.get(seat.section) ?? new Map<string, Seat[]>();
    const list = rows.get(seat.rowLabel) ?? [];
    list.push(seat);
    rows.set(seat.rowLabel, list);
    sections.set(seat.section, rows);
  }
  const natural = (a: string, b: string) => a.localeCompare(b, 'ko', { numeric: true });
  return [...sections]
    .sort(([a], [b]) => natural(a, b))
    .map(([name, rows]) => ({
      name,
      rows: [...rows]
        .sort(([a], [b]) => natural(a, b))
        .map(([label, list]) => ({ label, seats: list.sort((a, b) => a.seatNumber - b.seatNumber) })),
    }));
}
