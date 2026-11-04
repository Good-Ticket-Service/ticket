import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { PerformanceDetail, RemainingGrade, Schedule } from '../api/types';
import { artClass, ErrorNotice, Loading, Poster, SectionTitle } from '../components/Common';
import { useMe } from '../components/Layout';
import { entryToken } from '../lib/entryTokens';
import { formatDateTime, won } from '../lib/format';
import { genreLabel } from '../lib/genres';
import { ageGuidanceLabel, SALES_STATUS_LABEL as SALES } from '../lib/labels';

function scheduleNote(schedule: Schedule): string {
  if (schedule.salesStatus === 'BEFORE_OPEN') return `${formatDateTime(schedule.bookingOpenAt)} 오픈`;
  if (schedule.salesStatus === 'ON_SALE') return `${formatDateTime(schedule.bookingCloseAt)}까지 예매`;
  return SALES[schedule.salesStatus];
}

export function PerformanceDetailPage() {
  const { performanceId } = useParams();
  const [picked, setPicked] = useState<number | null>(null);
  const me = useMe();
  const performance = useQuery({
    queryKey: ['performance', performanceId],
    queryFn: () => api<PerformanceDetail>(`/api/performances/${performanceId}`),
  });
  const schedules = useQuery({
    queryKey: ['schedules', performanceId],
    queryFn: () => api<{ schedules: Schedule[] }>(`/api/performances/${performanceId}/schedules`),
  });
  const firstOnSale = schedules.data?.schedules.find((s) => s.salesStatus === 'ON_SALE')?.id ?? null;
  const scheduleId = picked ?? firstOnSale;
  const remaining = useQuery({
    queryKey: ['remaining', scheduleId],
    enabled: scheduleId !== null,
    queryFn: () => api<{ grades: RemainingGrade[] }>(`/api/seats/schedules/${scheduleId}/remaining`),
  });
  if (performance.isPending) return <main className="content"><Loading /></main>;
  if (performance.isError) return <main className="content"><ErrorNotice error={performance.error} /></main>;
  const item = performance.data;
  const seatsPath = `/performances/${item.id}/schedules/${scheduleId}/seats`;
  // 대기열이 꺼져 있으면 대기열 화면이 바로 좌석으로 넘긴다. 이미 입장했으면 줄을 다시 서지 않는다.
  const next = entryToken(item.id) ? seatsPath : `/performances/${item.id}/queue?scheduleId=${scheduleId}`;
  const bookable = !me.data || me.data.role === 'USER';
  return (
    <main className="content">
      <div className="split">
        <section className={`detail-hero ${artClass(item.id)}`}>
          <Poster src={item.posterUrl} />
          <div className="scrim" />
          <div className="body">
            <span className="tag">{genreLabel(item.genre)}</span>
            <h1>{item.title}</h1>
            <span className="muted">
              {item.venue.name} · {item.venue.region}
            </span>
            <span className="muted">
              연령: {ageGuidanceLabel(item.ageGuidance, item.ageRating)} · {item.runningTime}분
            </span>
          </div>
        </section>
        <div className="main stack" style={{ gap: 30 }}>
          <section>
            <SectionTitle en="Schedule" ko="회차" />
            {schedules.isPending && <Loading />}
            {schedules.isError && <ErrorNotice error={schedules.error} />}
            {schedules.data?.schedules.length === 0 && <p className="empty">등록된 회차가 없어요.</p>}
            {schedules.data?.schedules.map((schedule) => {
              const onSale = schedule.salesStatus === 'ON_SALE';
              const selected = schedule.id === scheduleId;
              return (
                <button
                  key={schedule.id}
                  type="button"
                  className="schedule"
                  aria-pressed={selected}
                  disabled={!onSale}
                  onClick={() => setPicked(schedule.id)}
                >
                  <span>
                    <span className="when num">{formatDateTime(schedule.startAt)}</span>
                    <br />
                    <span className="sub num">{scheduleNote(schedule)}</span>
                  </span>
                  <span className={onSale ? (selected ? 'pill solid' : 'pill on') : 'pill'}>
                    {selected ? '선택됨' : SALES[schedule.salesStatus]}
                  </span>
                </button>
              );
            })}
          </section>
          {remaining.data && (
            <section>
              <SectionTitle en="Seats left" ko="잔여석" />
              <div className="grades">
                {remaining.data.grades.map((grade) => {
                  const price = item.gradePrices.find((p) => p.venueSeatGradeId === grade.venueSeatGradeId)?.price;
                  return (
                    <div key={grade.venueSeatGradeId} className="grade">
                      <div className="name">{grade.gradeName}</div>
                      <div className="value num">{grade.remaining === 0 ? '매진' : `${grade.remaining}석`}</div>
                      {price !== undefined && <div className="name num">{won(price)}</div>}
                    </div>
                  );
                })}
              </div>
            </section>
          )}
          {!remaining.data && item.gradePrices.length > 0 && (
            <section>
              <SectionTitle en="Prices" ko="가격" />
              <div className="grades">
                {item.gradePrices.map((price) => (
                  <div key={price.venueSeatGradeId} className="grade">
                    <div className="name">{price.gradeName}</div>
                    <div className="value num">{won(price.price)}</div>
                  </div>
                ))}
              </div>
            </section>
          )}
          {item.description && <p className="muted" style={{ lineHeight: 1.7 }}>{item.description}</p>}
          <div className="sticky-cta">
            {!bookable ? (
              <button className="btn btn-primary btn-block" disabled>
                일반 회원만 예매할 수 있어요
              </button>
            ) : scheduleId === null ? (
              <button className="btn btn-primary btn-block" disabled>
                예매할 수 있는 회차가 없어요
              </button>
            ) : (
              <Link className="btn btn-primary btn-block" to={next}>
                예매하기
              </Link>
            )}
          </div>
        </div>
      </div>
    </main>
  );
}
