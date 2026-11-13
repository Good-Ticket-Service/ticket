import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useSearchParams, Link } from 'react-router-dom';
import { api } from '../api/client';
import type { PageC, PerformanceSummary } from '../api/types';
import { ErrorNotice, Loading } from '../components/Common';
import { PerformanceCard } from '../components/PerformanceCard';
import { GENRE_OPTIONS } from '../lib/genres';
import { SALE_STATUS_OPTIONS, parseSaleStatuses, saleStatusParam, toggleSaleStatus } from '../lib/saleStatus';

export function SearchPage() {
  const [search, setSearch] = useSearchParams();
  const keyword = (search.get('keyword') ?? '').trim();
  const genre = GENRE_OPTIONS.some((item) => item.value === search.get('genre')) ? search.get('genre')! : '';
  const sort = search.get('sort') === 'BOOKING_OPEN_ASC' ? 'BOOKING_OPEN_ASC' : 'LATEST';
  const pageValue = Number(search.get('page') ?? 0);
  const page = Number.isSafeInteger(pageValue) && pageValue >= 0 ? pageValue : 0;
  const params = new URLSearchParams({ page: String(page), size: '20', sort });
  if (keyword) params.set('keyword', keyword);
  if (genre) params.set('genre', genre);
  // 판매 상태: 기본(판매중·판매예정)이면 보내지 않는다. 빈 status는 서버가 400으로 거절한다(이슈 #382).
  const statuses = parseSaleStatuses(search.get('status'));
  const statusParam = saleStatusParam(statuses);
  if (statusParam) params.set('status', statusParam);
  const query = useQuery({
    queryKey: ['performances', params.toString()],
    queryFn: () => api<PageC<PerformanceSummary>>(`/api/performances?${params}`),
    placeholderData: keepPreviousData,
  });
  const update = (key: string, value: string) => {
    const next = new URLSearchParams(search);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== 'page') next.delete('page');
    setSearch(next);
  };
  const clearFilters = () => setSearch(keyword ? { keyword } : {});
  const lastPage = query.data ? Math.max(0, query.data.totalPages - 1) : 0;
  return (
    <main className="content search-content">
      <header className="search-heading">
        <Link to="/" className="btn-link">← 메인으로</Link>
        <p className="eyebrow">공연 검색</p>
        <h1>{keyword ? <>‘{keyword}’ 검색 결과</> : '공연 둘러보기'}</h1>
        {!keyword && <p>상단 검색창에서 찾고 싶은 공연을 검색해 보세요.</p>}
      </header>
      <div className="search-results-heading">
        <p className="search-result-count" aria-live="polite">
          {query.isSuccess && <>검색 결과 <strong className="num">{query.data.totalElements}</strong><span>개</span></>}
        </p>
        <div className="search-sort" role="group" aria-label="검색 결과 정렬">
          {[
            { value: 'LATEST', label: '최근 공개순' },
            { value: 'BOOKING_OPEN_ASC', label: '예매 오픈순' },
          ].map((option) => (
            <button key={option.value} type="button" aria-pressed={sort === option.value}
              onClick={() => update('sort', option.value)}>{option.label}</button>
          ))}
        </div>
      </div>
      <div className="search-layout">
        <aside className="search-filters" aria-labelledby="search-filter-title">
          <div className="search-filter-heading">
            <h2 id="search-filter-title">필터</h2>
            <button className="search-reset" type="button" onClick={clearFilters} aria-label="검색 필터 초기화">
              <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
                <path d="M4 6a6 6 0 1 1-1 5M4 2v4h4" />
              </svg>초기화
            </button>
          </div>
          <fieldset>
            <legend>장르</legend>
            <div className="search-genre-options">
              {GENRE_OPTIONS.map((item) => (
                <button key={item.value} className="chip" type="button" aria-pressed={genre === item.value}
                  onClick={() => update('genre', genre === item.value ? '' : item.value)}>{item.label}</button>
              ))}
            </div>
          </fieldset>
          <p className="search-filter-hint">선택한 장르를 다시 누르면 해제돼요.</p>
          <fieldset>
            <legend>판매 상태</legend>
            <div className="search-genre-options">
              {SALE_STATUS_OPTIONS.map((item) => {
                const selected = statuses.includes(item.value);
                const onlyOne = selected && statuses.length === 1;
                return (
                  <button key={item.value} className="chip" type="button" aria-pressed={selected}
                    disabled={onlyOne} title={onlyOne ? '판매 상태는 하나 이상 선택해야 해요.' : undefined}
                    onClick={() => update('status', saleStatusParam(toggleSaleStatus(statuses, item.value)) ?? '')}>
                    {item.label}
                  </button>
                );
              })}
            </div>
          </fieldset>
          <p className="search-filter-hint">판매종료를 켜면 끝난 공연도 함께 보여요.</p>
        </aside>
        <section className="search-results" aria-label="공연 검색 결과" aria-busy={query.isFetching}>
          {query.isPending && <Loading />}
          {query.isError && (
            <div className="home-error">
              <ErrorNotice error={query.error} />
              <button className="btn btn-ghost" type="button" onClick={() => query.refetch()}>다시 불러오기</button>
            </div>
          )}
          {query.isSuccess && query.data.content.length === 0 && (
            <div className="home-empty">
              <h2>조건에 맞는 공연이 없어요.</h2>
              <p>검색어를 바꾸거나 장르·판매 상태 필터를 초기화해 보세요.</p>
              <button className="btn-link" type="button" onClick={clearFilters}>필터 초기화</button>
            </div>
          )}
          {query.isSuccess && (
            <div className="poster-grid search-poster-grid">
              {query.data.content.map((item) => <PerformanceCard key={item.id} item={item} />)}
            </div>
          )}
          {lastPage > 0 && query.isSuccess && (
            <nav className="pager" aria-label="검색 결과 페이지">
              <button className="chip" type="button" disabled={page === 0 || query.isPlaceholderData}
                onClick={() => update('page', String(page - 1))}>이전</button>
              <span className="num">{page + 1} / {lastPage + 1}</span>
              <button className="chip" type="button" disabled={page >= lastPage || query.isPlaceholderData}
                onClick={() => update('page', String(page + 1))}>다음</button>
            </nav>
          )}
        </section>
      </div>
    </main>
  );
}
