import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { PerformanceDetail, Schedule } from '../api/types';
import { ErrorNotice, Loading, Poster } from '../components/Common';
import { PerformanceBooking } from '../components/PerformanceBooking';
import { formatDate, won } from '../lib/format';
import { genreLabel } from '../lib/genres';
import { ageGuidanceLabel } from '../lib/labels';

export function PerformanceDetailPage() {
  const { performanceId } = useParams();
  const performance = useQuery({
    queryKey: ['performance', performanceId],
    queryFn: () => api<PerformanceDetail>(`/api/performances/${performanceId}`),
  });
  const schedules = useQuery({
    queryKey: ['schedules', performanceId],
    queryFn: () => api<{ schedules: Schedule[] }>(`/api/performances/${performanceId}/schedules`),
  });
  if (performance.isPending)
    return (
      <main className="content">
        <Loading />
      </main>
    );
  if (performance.isError)
    return (
      <main className="content">
        <ErrorNotice error={performance.error} />
        <button type="button" className="btn btn-ghost" onClick={() => performance.refetch()}>
          다시 불러오기
        </button>
      </main>
    );
  const item = performance.data;
  return (
    <main className="content performance-detail">
      <nav aria-label="현재 위치" className="performance-breadcrumb">
        <Link to="/">공연</Link>
        <span aria-hidden="true">/</span>
        <Link to={`/?genre=${item.genre}#performances`}>{genreLabel(item.genre)}</Link>
      </nav>
      <div className="performance-detail-layout">
        <div className="performance-information">
          <section className="performance-overview" aria-labelledby="performance-title">
            <div className="detail-poster">
              <Poster key={item.id} src={item.posterUrl} />
              <span className="detail-poster-fallback" aria-hidden="true">
                <span>safe ticket</span>
                <strong>{item.title}</strong>
                <span>{genreLabel(item.genre)}</span>
              </span>
            </div>
            <div className="performance-facts">
              <span className="detail-genre">{genreLabel(item.genre)}</span>
              <h1 id="performance-title">{item.title}</h1>
              <dl className="performance-metadata">
                <div>
                  <dt>공연장</dt>
                  <dd>{item.venue.name}</dd>
                </div>
                <div>
                  <dt>공연기간</dt>
                  <dd>
                    {item.period
                      ? `${formatDate(item.period.from)} — ${formatDate(item.period.to)}`
                      : '등록된 일정이 없어요'}
                  </dd>
                </div>
                <div>
                  <dt>관람시간</dt>
                  <dd>{item.runningTime}분</dd>
                </div>
                <div>
                  <dt>관람연령</dt>
                  <dd>{ageGuidanceLabel(item.ageGuidance, item.ageRating)}</dd>
                </div>
                <div>
                  <dt>티켓가격</dt>
                  <dd>
                    {item.gradePrices.length > 0
                      ? item.gradePrices.map((grade) => (
                          <span className="metadata-price" key={grade.venueSeatGradeId}>
                            {grade.gradeName} {won(grade.price)}
                          </span>
                        ))
                      : '가격 정보 미등록'}
                  </dd>
                </div>
              </dl>
              <Link className="detail-coupon-link" to="/coupons">
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.6"
                  aria-hidden="true"
                >
                  <path d="M3 5h18v5a2 2 0 0 0 0 4v5H3v-5a2 2 0 0 0 0-4Z" />
                  <path d="M15 5v14" strokeDasharray="2 2" />
                </svg>
                <span>쿠폰 혜택 확인</span>
                <span aria-hidden="true">›</span>
              </Link>
            </div>
          </section>
          <nav className="detail-section-links" aria-label="공연 상세 정보">
            <a href="#performance-description">공연정보</a>
            {item.castInfo && <a href="#performance-cast">출연정보</a>}
            <a href="#performance-venue">공연장</a>
          </nav>
          <section id="performance-description" className="detail-text-section">
            <h2 className="section-marker">공연 소개</h2>
            <p>{item.description || '등록된 공연 소개가 없어요.'}</p>
          </section>
          {item.castInfo && (
            <section id="performance-cast" className="detail-text-section">
              <h2 className="section-marker">출연정보</h2>
              <p>{item.castInfo}</p>
            </section>
          )}
          <section id="performance-venue" className="detail-text-section">
            <h2 className="section-marker">공연장 안내</h2>
            <strong>{item.venue.name}</strong>
            <p>{item.venue.address || item.venue.region}</p>
          </section>
        </div>
        {schedules.isSuccess ? (
          <PerformanceBooking
            key={item.id}
            item={item}
            schedules={schedules.data.schedules}
            refresh={() => schedules.refetch()}
          />
        ) : (
          <aside className="performance-booking">
            <h2>예매하기</h2>
            {schedules.isPending ? (
              <Loading />
            ) : (
              <>
                <ErrorNotice error={schedules.error} />
                <button type="button" className="btn btn-ghost" onClick={() => schedules.refetch()}>
                  회차 다시 불러오기
                </button>
              </>
            )}
          </aside>
        )}
      </div>
    </main>
  );
}
