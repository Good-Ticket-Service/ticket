import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { PageP, PerformanceSummary } from '../api/types';
import { artClass, ErrorNotice, GENRE_LABEL, Loading, Poster, Title } from '../components/Common';
import { formatDate } from '../lib/format';

const GENRES = [
  { value: '', label: '전체' },
  { value: 'MUSICAL', label: '뮤지컬' },
  { value: 'CONCERT', label: '콘서트' },
  { value: 'PLAY', label: '연극' },
  { value: 'SPORTS', label: '스포츠' },
];

export function PerformancesPage() {
  const [genre, setGenre] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (genre) params.set('genre', genre);
  if (keyword.trim()) params.set('keyword', keyword.trim());
  const query = useQuery({
    queryKey: ['performances', params.toString()],
    queryFn: () => api<PageP<PerformanceSummary>>(`/api/performances?${params}`),
  });
  const lastPage = query.data ? Math.max(0, Math.ceil(query.data.totalElements / query.data.size) - 1) : 0;
  return (
    <main className="content">
      <Title en="Performances" ko="공연" />
      <div className="toolbar">
        <div className="chips">
          {GENRES.map((item) => (
            <button
              key={item.value}
              type="button"
              className="chip"
              aria-pressed={genre === item.value}
              onClick={() => {
                setGenre(item.value);
                setPage(0);
              }}
            >
              {item.label}
            </button>
          ))}
        </div>
        <div className="search">
          <label htmlFor="search" className="sr-only">
            공연 검색
          </label>
          <input
            id="search"
            className="input"
            type="search"
            placeholder="공연명 검색"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPage(0);
            }}
          />
        </div>
      </div>
      {query.isPending && <Loading />}
      {query.isError && <ErrorNotice error={query.error} />}
      {query.data?.items.length === 0 && <p className="empty">조건에 맞는 공연이 없어요.</p>}
      <section className="card-grid">
        {query.data?.items.map((item) => (
          <Link key={item.id} to={`/performances/${item.id}`} className={`hero-card ${artClass(item.id)}`}>
            <Poster src={item.posterUrl} />
            <span className="scrim" />
            <span className="body">
              <span className="tag">{GENRE_LABEL[item.genre] ?? item.genre}</span>
              <span className="name">{item.title}</span>
              <span className="meta">
                {item.venue.name} · {item.venue.region}
              </span>
              {item.period && (
                <span className="meta num">
                  {formatDate(item.period.from)} ~ {formatDate(item.period.to)}
                </span>
              )}
            </span>
          </Link>
        ))}
      </section>
      {lastPage > 0 && (
        <div className="pager">
          <button type="button" className="chip" disabled={page === 0} onClick={() => setPage(page - 1)}>
            이전
          </button>
          <span className="num">
            {page + 1} / {lastPage + 1}
          </span>
          <button type="button" className="chip" disabled={page === lastPage} onClick={() => setPage(page + 1)}>
            다음
          </button>
        </div>
      )}
    </main>
  );
}
