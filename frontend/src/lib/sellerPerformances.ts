export interface PerformanceRecord {
  id: number;
  title: string;
  venueId: number;
  version: number;
  versionKnown?: boolean;
  metadataUnknown?: boolean;
  publicationStatus: string;
  gradePrices: { venueSeatGradeId: number; gradeName: string; price: number }[];
  scheduleIds: number[];
  hasIntro?: boolean;
  posterUrl?: string | null;
  venueName?: string;
}
export const PERFORMANCE_RECORDS = 'ticket.seller.performances';
export function filterPerformanceRecords(records: PerformanceRecord[], keyword: string, status: string) {
  const key = keyword.trim().toLocaleLowerCase();
  return records.filter(
    (item) =>
      (!status || item.publicationStatus === status) &&
      (!key || item.title.toLocaleLowerCase().includes(key) || String(item.id).includes(key)),
  );
}
export function performanceVersion(record: PerformanceRecord | undefined, entered: string): number | null {
  if (entered !== '') {
    const version = Number(entered);
    return /^\d+$/.test(entered) && Number.isSafeInteger(version) && version >= 0 ? version : null;
  }
  return record &&
    record.versionKnown !== false &&
    (record.publicationStatus !== 'UNKNOWN' || record.versionKnown === true) &&
    Number.isSafeInteger(record.version) &&
    record.version >= 0
    ? record.version
    : null;
}
export function validScheduleTimes(value: {
  startAt: string;
  bookingOpenAt: string;
  bookingCloseAt: string;
}) {
  const time = (text: string) => Date.parse(text + '+09:00');
  const start = time(value.startAt),
    open = time(value.bookingOpenAt),
    close = time(value.bookingCloseAt);
  return (
    Number.isFinite(start) &&
    Number.isFinite(open) &&
    Number.isFinite(close) &&
    open < close &&
    close <= start
  );
}
