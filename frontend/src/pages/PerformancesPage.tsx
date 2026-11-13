import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useSearchParams, Link } from 'react-router-dom';
import { api } from '../api/client';
import type { PageC, PerformanceSummary } from '../api/types';
import { ErrorNotice, Loading, Poster } from '../components/Common';
import { GenreIcon } from '../components/GenreIcon';
import { HomeHero } from '../components/HomeHero';
import { TicketOpenings } from '../components/TicketOpenings';
import { GENRE_OPTIONS, genreLabel } from '../lib/genres';
import { PerformanceCard } from '../components/PerformanceCard';

const GENRES = [{ value: '', label: '전체' }, ...GENRE_OPTIONS];
const EDITORIALS = [
  {
    genre: 'PLAY',
    title: '퇴근 후, 연극 한 편',
    description: '가까운 무대에서 만나는 이야기',
    className: 'editorial-play',
  },
  {
    genre: 'CONCERT',
    title: '라이브로 채우는 주말',
    description: '직접 듣고 싶은 목소리',
    className: 'editorial-concert',
  },
  {
    genre: 'CLASSICAL',
    title: '차분하게 즐기는 클래식',
    description: '오래 듣고 싶은 선율',
    className: 'editorial-classical',
  },
];

export function PerformancesPage() {
  const [search, setSearch] = useSearchParams();
  const genre = GENRES.some((item) => item.value === search.get('genre')) ? (search.get('genre') ?? '') : '';
  const pageValue = Number(search.get('page') ?? 0);
  const page = Number.isSafeInteger(pageValue) && pageValue >= 0 ? pageValue : 0;
  const params = new URLSearchParams({ page: String(page), size: '5' });
  if (genre) params.set('genre', genre);
  const query = useQuery({
    queryKey: ['performances', params.toString()],
    queryFn: () => api<PageC<PerformanceSummary>>(`/api/performances?${params}`),
    placeholderData: keepPreviousData,
  });
  const featured = useQuery({
    queryKey: ['performances', 'page=0&size=5'],
    queryFn: () => api<PageC<PerformanceSummary>>('/api/performances?page=0&size=5'),
    staleTime: 60_000,
  });
  const items = featured.data?.content ?? [];
  const lastPage = query.data ? Math.max(0, query.data.totalPages - 1) : 0;
  const filter = (value: string) => {
    const next = new URLSearchParams(search);
    if (value) next.set('genre', value);
    else next.delete('genre');
    next.delete('page');
    setSearch(next, { preventScrollReset: true });
  };
  const changePage = (value: number) => {
    const next = new URLSearchParams(search);
    if (value) next.set('page', String(value));
    else next.delete('page');
    setSearch(next, { preventScrollReset: true });
    document.getElementById('performances')?.scrollIntoView({ block: 'start' });
  };
  return (
    <main className="content home-content">
      {featured.isPending && (
        <section className="hero-placeholder" aria-label="공연 소개">
          <Loading />
        </section>
      )}
      {featured.isError && (
        <section className="hero-placeholder">
          <h1>공연 정보를 불러오지 못했어요</h1>
          <ErrorNotice error={featured.error} />
          <button type="button" className="btn btn-ghost" onClick={() => featured.refetch()}>
            다시 불러오기
          </button>
        </section>
      )}
      {featured.isSuccess && items.length === 0 && (
        <section className="hero-placeholder">
          <h1>새로운 공연을 준비하고 있어요</h1>
          <p>공개된 공연이 등록되면 여기에서 만나볼 수 있어요.</p>
        </section>
      )}
      <HomeHero items={items} />
      <aside className="home-notice">
        <span>예매 안내</span>
        <p>공연별 관람 일정과 예매 가능 회차는 상세 페이지에서 확인하세요.</p>
      </aside>
      <section id="performances" className="home-section" aria-labelledby="performances-title">
        <div className="home-section-heading">
          <h2 id="performances-title" className="section-marker">
            지금 주목할 공연
          </h2>
          {query.data && (
            <span className="result-count">
              총 <strong className="num">{query.data.totalElements}</strong>개
            </span>
          )}
        </div>
        <div className="genre-options" aria-label="공연 장르">
          {GENRES.map((item) => (
            <button
              key={item.value}
              type="button"
              className="genre-option"
              aria-pressed={genre === item.value}
              onClick={() => filter(item.value)}
            >
              <GenreIcon genre={item.value} />
              <span>{item.label}</span>
            </button>
          ))}
        </div>
        <div className="performance-results" aria-busy={query.isFetching}>
          {query.isPending && <Loading />}
          {query.isError && (
            <div className="home-error">
              <ErrorNotice error={query.error} />
              <button className="btn btn-ghost" type="button" onClick={() => query.refetch()}>
                다시 불러오기
              </button>
            </div>
          )}
          {query.data?.content.length === 0 && (
            <div className="home-empty">
              <p>조건에 맞는 공연이 없어요.</p>
              <button type="button" className="btn-link" onClick={() => setSearch({})}>
                전체 공연 보기
              </button>
            </div>
          )}
          <div className="poster-grid">
            {query.data?.content.map((item) => (
              <PerformanceCard key={item.id} item={item} />
            ))}
          </div>
          {lastPage > 0 && (
            <nav className="pager" aria-label="공연 목록 페이지">
              <button
                type="button"
                className="chip"
                disabled={page === 0 || query.isPlaceholderData}
                onClick={() => changePage(page - 1)}
              >
                이전
              </button>
              <span className="num">
                {page + 1} / {lastPage + 1}
              </span>
              <button
                type="button"
                className="chip"
                disabled={page >= lastPage || query.isPlaceholderData}
                onClick={() => changePage(page + 1)}
              >
                다음
              </button>
            </nav>
          )}
        </div>
      </section>
      <TicketOpenings items={items} pending={featured.isPending} error={featured.error} />
      <section className="home-section" aria-labelledby="editorial-title">
        <div className="home-section-heading">
          <div>
            <h2 id="editorial-title">오늘, 취향 따라</h2>
            <p>어떤 하루를 보내고 싶나요?</p>
          </div>
        </div>
        <div className="editorial-grid">
          {EDITORIALS.map((editorial) => {
            const artwork = items.find((item) => item.genre === editorial.genre);
            return (
              <Link
                key={editorial.genre}
                to={`/?genre=${editorial.genre}#performances`}
                className={`editorial-card ${editorial.className}`}
              >
                {artwork?.posterUrl && <Poster src={artwork.posterUrl} />}
                <span className="editorial-symbol" aria-hidden="true">
                  <GenreIcon genre={editorial.genre} />
                </span>
                <span className="editorial-copy">
                  <span className="editorial-tag">#{genreLabel(editorial.genre)}</span>
                  <strong>{editorial.title}</strong>
                  <span>{editorial.description}</span>
                  <span className="editorial-link">공연 보기 ↗</span>
                </span>
              </Link>
            );
          })}
        </div>
      </section>
    </main>
  );
}
