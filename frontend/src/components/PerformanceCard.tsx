import { Link } from 'react-router-dom';
import type { PerformanceSummary } from '../api/types';
import { Poster } from './Common';
import { formatDate } from '../lib/format';
import { genreLabel } from '../lib/genres';
import { isEndedByPeriod } from '../lib/saleStatus';

export function PerformanceCard({ item }: { item: PerformanceSummary }) {
  // 응답에 판매 상태가 없어 마지막 공연 날짜로만 판매종료를 표시한다(오늘 끝난 공연은 표시하지 않음).
  const ended = isEndedByPeriod(item.period, Date.now());
  return (
    <Link to={`/performances/${item.id}`} className="performance-card">
      <span className="performance-poster">
        <Poster src={item.posterUrl} />
        <span className="poster-fallback" aria-hidden="true">
          <span>safe ticket</span>
          <strong>{item.title}</strong>
          <span>{genreLabel(item.genre)}</span>
        </span>
      </span>
      <strong className="performance-name">
        <span>
          {ended && <span className="badge-ended">판매종료</span>}
          {item.title}
        </span>
      </strong>
      <span className="performance-venue">
        {item.venue.name} · {item.venue.region}
      </span>
      {item.period && (
        <span className="performance-period num">
          {formatDate(item.period.from)} — {formatDate(item.period.to)}
        </span>
      )}
      <span className="performance-genre">{genreLabel(item.genre)}</span>
    </Link>
  );
}
