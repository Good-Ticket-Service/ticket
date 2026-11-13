import { useQuery } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, getToken } from '../api/client';
import type { SettlementPreview } from '../api/settlementTypes';
import { ErrorNotice, Loading } from './Common';
import { formatDate, formatDateTime, won } from '../lib/format';
import {
  settlementPage,
  settlementPeriodError,
  settlementRange,
  validScheduleFilter,
} from '../lib/settlements';

export function SettlementPreviewPanel() {
  const [params, setParams] = useSearchParams();
  const defaults = settlementRange();
  const from = params.get('from') ?? defaults.from,
    to = params.get('to') ?? defaults.to,
    scheduleId = params.get('scheduleId') ?? '',
    page = settlementPage(params.get('page'));
  const [form, setForm] = useState({ from, to, scheduleId });
  const [error, setError] = useState<string | null>(null);
  const invalid =
    settlementPeriodError(from, to, true) ||
    (!validScheduleFilter(scheduleId) ? '올바른 회차 ID를 입력해 주세요.' : null);
  const request = new URLSearchParams({ from, to, page: String(page), size: '10' });
  if (scheduleId) request.set('scheduleId', scheduleId);
  const query = useQuery({
    queryKey: ['seller-preview', getToken(), request.toString()],
    enabled: !invalid,
    queryFn: ({ signal }) =>
      api<SettlementPreview>(`/api/seller/settlements/preview?${request}`, { cache: 'no-store', signal }),
  });
  const apply = (e: FormEvent) => {
    e.preventDefault();
    const reason =
      settlementPeriodError(form.from, form.to, true) ||
      (!validScheduleFilter(form.scheduleId) ? '올바른 회차 ID를 입력해 주세요.' : null);
    setError(reason);
    if (reason) return;
    const next = new URLSearchParams({ view: 'preview', from: form.from, to: form.to });
    if (form.scheduleId) next.set('scheduleId', form.scheduleId);
    setParams(next, { preventScrollReset: true });
  };
  const data = query.data;
  const changePage = (nextPage: number) => {
    const next = new URLSearchParams({ view: 'preview', from, to, page: String(nextPage) });
    if (scheduleId) next.set('scheduleId', scheduleId);
    setParams(next, { preventScrollReset: true });
  };
  return (
    <section className="stack settlement-preview">
      <div className="settlement-panel-heading">
        <div>
          <h2 className="panel-title">예상 정산</h2>
          <p className="account-hint">서울 기준 금융 발생일 · 최대 31일</p>
        </div>
        <button
          className="chip"
          type="button"
          disabled={query.isFetching || !!invalid}
          onClick={() => query.refetch()}
        >
          {query.isFetching ? '확인 중' : '새로고침'}
        </button>
      </div>
      <p className="settlement-explanation">
        판매자 몫의 판매와 완료 환불을 반영한 기간 순변동입니다. 지급 가능액·현금 잔액·미지급액과 다릅니다.
      </p>
      <form className="settlement-filters" onSubmit={apply}>
        <div className="field">
          <label htmlFor="preview-from">조회 시작일</label>
          <input
            id="preview-from"
            className="input"
            type="date"
            required
            value={form.from}
            onInput={(e) => setForm({ ...form, from: e.currentTarget.value })}
          />
        </div>
        <div className="field">
          <label htmlFor="preview-to">조회 종료일</label>
          <input
            id="preview-to"
            className="input"
            type="date"
            required
            value={form.to}
            onInput={(e) => setForm({ ...form, to: e.currentTarget.value })}
          />
        </div>
        <div className="field">
          <label htmlFor="preview-schedule">회차 ID (선택)</label>
          <input
            id="preview-schedule"
            className="input"
            type="number"
            min={1}
            max={Number.MAX_SAFE_INTEGER}
            step={1}
            value={form.scheduleId}
            onChange={(e) => setForm({ ...form, scheduleId: e.target.value })}
            placeholder="전체 회차"
          />
        </div>
        <button className="btn btn-ghost">조회</button>
      </form>
      {(error || invalid) && (
        <p className="notice" role="alert">
          {error || invalid}
        </p>
      )}
      {query.isPending && !invalid && <Loading />}
      {query.isError && <ErrorNotice error={query.error} />}
      {query.isError && data && (
        <p className="settlement-warning">이전 조회 결과입니다. 최신 집계 상태를 확인하지 못했어요.</p>
      )}
      {data && (
        <>
          {data.status === 'INITIALIZING' ? (
            <div className="settlement-initializing" role="status">
              <h3>예상 정산을 준비하고 있어요</h3>
              <p>아직 집계된 금액을 표시할 수 없어요. 잠시 후 새로고침해 주세요.</p>
            </div>
          ) : (
            <>
              {data.status === 'STALE' && (
                <p className="settlement-warning" role="status">
                  집계 갱신이 지연되거나 미반영 내역이 있어요. 마지막 검증된 집계 값이며, 최신 금액과 다를 수
                  있어요.
                </p>
              )}
              {data.totals && (
                <div className="settlement-metrics">
                  <article>
                    <h3>판매자 몫 판매</h3>
                    <strong>{won(data.totals.saleSellerAmount)}</strong>
                    <span>{data.totals.saleCount.toLocaleString('ko-KR')}건</span>
                  </article>
                  <article>
                    <h3>완료 환불 차감</h3>
                    <strong>{won(data.totals.refundSellerAmount)}</strong>
                    <span>{data.totals.refundCount.toLocaleString('ko-KR')}건</span>
                  </article>
                  <article className="settlement-net">
                    <h3>기간 순변동</h3>
                    <strong>{won(data.totals.netSellerAmount)}</strong>
                    <span>
                      {formatDate(data.from)} — {formatDate(data.to)}
                    </span>
                  </article>
                </div>
              )}
              <section className="panel stack">
                <h3 className="panel-title">일자·회차별 내역</h3>
                <div className="seller-table-scroll">
                  <table className="seller-table settlement-table">
                    <thead>
                      <tr>
                        <th>금융 발생일 · 회차</th>
                        <th>판매 건수</th>
                        <th>판매자 몫 판매</th>
                        <th>환불 건수</th>
                        <th>환불 차감</th>
                        <th>순변동</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.days.content.map((day) => (
                        <tr key={`${day.businessDate}-${day.scheduleId}`}>
                          <td>
                            {formatDate(day.businessDate)}
                            <span className="seller-cell-meta">회차 {day.scheduleId}</span>
                          </td>
                          <td>{day.totals.saleCount}</td>
                          <td>{won(day.totals.saleSellerAmount)}</td>
                          <td>{day.totals.refundCount}</td>
                          <td>{won(day.totals.refundSellerAmount)}</td>
                          <td>
                            <strong>{won(day.totals.netSellerAmount)}</strong>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {data.days.content.length === 0 && (
                  <p className="seller-empty">해당 기간에 집계된 내역이 없어요.</p>
                )}
                {data.days.totalPages > 1 && (
                  <nav className="pager" aria-label="예상 정산 페이지">
                    <button
                      className="chip"
                      type="button"
                      disabled={page === 0 || query.isFetching}
                      onClick={() => changePage(page - 1)}
                    >
                      이전
                    </button>
                    <span>
                      {page + 1} / {data.days.totalPages}
                    </span>
                    <button
                      className="chip"
                      type="button"
                      disabled={page + 1 >= data.days.totalPages || page >= 10000 || query.isFetching}
                      onClick={() => changePage(page + 1)}
                    >
                      다음
                    </button>
                  </nav>
                )}
              </section>
              {data.health && (
                <details className="settlement-health">
                  <summary>집계 반영 상태 확인</summary>
                  <div className="settlement-health-grid">
                    <section>
                      <h3>조회 기간·회차 기준</h3>
                      <dl>
                        {[
                          ['미발행 집계', data.health.unpublished],
                          ['미반영 집계', data.health.unapplied],
                          ['집계 오류', data.health.errors],
                        ].map(([label, value]) => (
                          <div key={label}>
                            <dt>{label}</dt>
                            <dd>{value}건</dd>
                          </div>
                        ))}
                      </dl>
                    </section>
                    <section>
                      <h3>판매자 전체 기준</h3>
                      <dl>
                        {[
                          ['정산 처리 대기', data.health.originPending],
                          ['계산 미완료', data.health.unposted],
                          ['진행·확인 중 환불', data.health.pendingRefunds],
                        ].map(([label, value]) => (
                          <div key={label}>
                            <dt>{label}</dt>
                            <dd>{value}건</dd>
                          </div>
                        ))}
                      </dl>
                    </section>
                  </div>
                  <p>
                    마지막 집계 갱신{' '}
                    {data.health.lastFanoutAt ? formatDateTime(data.health.lastFanoutAt) : '확인할 수 없음'}
                  </p>
                  <p>
                    마지막 검증{' '}
                    {data.health.verifiedAt ? formatDateTime(data.health.verifiedAt) : '확인할 수 없음'}
                  </p>
                </details>
              )}
            </>
          )}
        </>
      )}
    </section>
  );
}
