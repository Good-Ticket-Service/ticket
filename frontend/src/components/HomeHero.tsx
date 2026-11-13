import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { PerformanceSummary } from '../api/types';
import { formatDate } from '../lib/format';
import { genreLabel } from '../lib/genres';
import { Poster } from './Common';

export function HomeHero({ items }: { items: PerformanceSummary[] }) {
  const [index, setIndex] = useState(0);
  const selected = index % Math.max(items.length, 1);
  const item = items[selected];
  if (!item) return null;
  return (
    <section className="home-hero" aria-label="주목할 공연 소개" aria-roledescription="캐러셀">
      <div className="hero-backdrop" aria-hidden="true">
        <Poster key={item.id} src={item.posterUrl} />
      </div>
      <div className="hero-info" aria-live="polite" aria-atomic="true">
        <span className="hero-eyebrow">
          {genreLabel(item.genre)} <span>·</span> NOW SHOWING
        </span>
        <h1>{item.title}</h1>
        <p className="hero-venue">{item.venue.name}</p>
        {item.period && (
          <p className="hero-period num">
            {formatDate(item.period.from)} — {formatDate(item.period.to)}
          </p>
        )}
        <Link className="btn btn-primary" to={`/performances/${item.id}`}>
          공연 상세 보기 <span aria-hidden="true">↗</span>
        </Link>
      </div>
      <Link className="hero-artwork" to={`/performances/${item.id}`} aria-label={`${item.title} 상세 보기`}>
        <div className="hero-poster">
          <Poster key={item.id} src={item.posterUrl} />
          <span>{item.title}</span>
        </div>
      </Link>
      {items.length > 1 && (
        <div className="hero-controls">
          <span className="num">
            {String(selected + 1).padStart(2, '0')} <span>/ {String(items.length).padStart(2, '0')}</span>
          </span>
          <button
            type="button"
            aria-label="이전 공연 소개"
            onClick={() => setIndex((selected + items.length - 1) % items.length)}
          >
            ‹
          </button>
          <button
            type="button"
            aria-label="다음 공연 소개"
            onClick={() => setIndex((selected + 1) % items.length)}
          >
            ›
          </button>
        </div>
      )}
    </section>
  );
}
