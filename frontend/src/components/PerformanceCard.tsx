import { Link } from 'react-router-dom';
import type { PerformanceSummary } from '../api/types';
import { Poster } from './Common';
import { formatDate } from '../lib/format';
import { genreLabel } from '../lib/genres';

export function PerformanceCard({ item }: { item: PerformanceSummary }) {
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
      <strong className="performance-name">{item.title}</strong>
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
