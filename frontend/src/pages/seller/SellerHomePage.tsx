import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Poster } from '../../components/Common';
import { SellerScheduleList } from '../../components/SellerScheduleList';
import { useLocalRecords } from '../../lib/localRecords';
import {
  filterPerformanceRecords,
  PERFORMANCE_RECORDS,
  type PerformanceRecord,
} from '../../lib/sellerPerformances';

export function SellerHomePage() {
  const navigate = useNavigate();
  const performances = useLocalRecords<PerformanceRecord>(PERFORMANCE_RECORDS);
  const [keyword, setKeyword] = useState(''),
    [status, setStatus] = useState('');
  const [performanceId, setPerformanceId] = useState(''),
    [scheduleId, setScheduleId] = useState('');
  const [selected, setSelected] = useState<number | null>(null);
  const [page, setPage] = useState(0);
  const list = filterPerformanceRecords(performances.records, keyword, status);
  const pageIndex = Math.min(page, Math.max(0, Math.ceil(list.length / 10) - 1));
  const chosen = performances.find(selected ?? 0);
  const open = (kind: 'performances' | 'schedules', value: string) => {
    if (/^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value))) navigate(`/seller/${kind}/${value}`);
  };
  return (
    <main className="content seller-page seller-home">
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">공연 관리</h1>
          <p>이 브라우저에 저장된 공연을 관리합니다.</p>
        </div>
        <Link className="btn btn-primary" to="/seller/performances/new">
          ＋ 공연 등록
        </Link>
      </header>
      <div className="seller-toolbar">
        <div className="field">
          <label className="sr-only" htmlFor="seller-search">
            저장된 공연명 또는 ID 검색
          </label>
          <input
            className="input"
            id="seller-search"
            type="search"
            placeholder="공연명 또는 ID 검색"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPage(0);
            }}
          />
        </div>
        <select
          className="input"
          aria-label="공연 공개 상태"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          <option value="">전체 상태</option>
          <option value="PUBLISHED">공개</option>
          <option value="DRAFT">초안</option>
          <option value="UNKNOWN">확인 필요</option>
        </select>
      </div>
      <section className="seller-list-panel" aria-label="브라우저 공연 기록">
        <div className="seller-table-scroll">
          <table className="seller-table seller-performances-table">
            <thead>
              <tr>
                <th>공연</th>
                <th>상태</th>
                <th>공연장</th>
                <th>회차</th>
                <th>관리</th>
              </tr>
            </thead>
            <tbody>
              {list.slice(pageIndex * 10, (pageIndex + 1) * 10).map((item) => (
                <tr key={item.id}>
                  <td>
                    <div className="seller-product">
                      <div className="seller-small-poster">
                        <Poster src={item.posterUrl ?? null} />
                        <span aria-hidden="true">ST</span>
                      </div>
                      <div>
                        <Link className="seller-performance-name" to={`/seller/performances/${item.id}`}>
                          {item.title}
                        </Link>
                        <span className="seller-cell-meta">공연 ID {item.id}</span>
                      </div>
                    </div>
                  </td>
                  <td>
                    <span
                      className={`seller-state ${item.publicationStatus === 'PUBLISHED' ? 'ready' : 'draft'}`}
                    >
                      {item.publicationStatus === 'PUBLISHED'
                        ? '공개'
                        : item.publicationStatus === 'DRAFT'
                          ? '초안'
                          : '확인 필요'}
                    </span>
                  </td>
                  <td>{item.venueName ?? (item.venueId ? `ID ${item.venueId}` : '확인 필요')}</td>
                  <td>
                    <button
                      className="seller-text-button"
                      type="button"
                      aria-expanded={selected === item.id}
                      aria-controls="seller-selected-schedules"
                      onClick={() => setSelected(selected === item.id ? null : item.id)}
                    >
                      {item.scheduleIds.length}개 보기
                    </button>
                  </td>
                  <td>
                    <Link className="chip" to={`/seller/performances/${item.id}`}>
                      관리 ›
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {list.length === 0 && (
          <div className="seller-empty">
            <p>
              {performances.records.length
                ? '검색 조건에 맞는 공연이 없어요.'
                : '아직 이 브라우저에 저장된 공연이 없어요.'}
            </p>
            <Link to="/seller/performances/new">공연 등록하기 ↗</Link>
          </div>
        )}
      </section>
      {list.length > 10 && (
        <nav className="pager" aria-label="공연 기록 페이지">
          <button
            type="button"
            className="chip"
            disabled={pageIndex === 0}
            onClick={() => setPage(pageIndex - 1)}
          >
            이전
          </button>
          <span>
            {pageIndex + 1} / {Math.ceil(list.length / 10)}
          </span>
          <button
            type="button"
            className="chip"
            disabled={(pageIndex + 1) * 10 >= list.length}
            onClick={() => setPage(pageIndex + 1)}
          >
            다음
          </button>
        </nav>
      )}
      <section id="seller-selected-schedules" className="seller-schedules-panel">
        {chosen && (
          <>
            <div className="seller-panel-heading">
              <h2>{chosen.title} · 회차 준비 상태</h2>
              <button className="seller-text-button" type="button" onClick={() => setSelected(null)}>
                접기
              </button>
            </div>
            <SellerScheduleList key={chosen.id} ids={chosen.scheduleIds} />
          </>
        )}
      </section>
      <p className="seller-storage-note">
        서버 전체 목록이 아닌 브라우저 기록입니다. 다른 기기에서 만든 공연은 ID로 열어 주세요. 회차 상태는
        서버에서 조회합니다.
      </p>
      <details className="seller-direct-open">
        <summary>공연·회차 ID로 직접 열기</summary>
        <div className="seller-direct-grid">
          {(['performances', 'schedules'] as const).map((kind) => (
            <form
              key={kind}
              onSubmit={(e) => {
                e.preventDefault();
                open(kind, kind === 'performances' ? performanceId : scheduleId);
              }}
            >
              <label htmlFor={`open-${kind}`}>{kind === 'performances' ? '공연 ID' : '회차 ID'}</label>
              <div className="actions">
                <input
                  id={`open-${kind}`}
                  className="input"
                  type="number"
                  min={1}
                  max={Number.MAX_SAFE_INTEGER}
                  step={1}
                  required
                  value={kind === 'performances' ? performanceId : scheduleId}
                  onChange={(e) =>
                    kind === 'performances' ? setPerformanceId(e.target.value) : setScheduleId(e.target.value)
                  }
                />
                <button className="btn btn-ghost">열기</button>
              </div>
            </form>
          ))}
        </div>
      </details>
      <details className="seller-direct-open">
        <summary>브라우저 기록 정리</summary>
        <p className="account-hint">서버의 공연은 삭제되지 않습니다. 이 브라우저에 저장한 기록만 지웁니다.</p>
        {performances.records.map((item) => (
          <div className="seller-record-cleanup" key={item.id}>
            <span>
              {item.title} · ID {item.id}
            </span>
            <button
              className="chip"
              type="button"
              onClick={() => performances.remove(item.id)}
              aria-label={`${item.title} 기록 지우기`}
            >
              기록 지우기
            </button>
          </div>
        ))}
      </details>
    </main>
  );
}
