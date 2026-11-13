import { useQuery } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import { api, getToken } from '../../api/client';
import type { StatementStatus, StatementSummary } from '../../api/manageTypes';
import type { PageC } from '../../api/types';
import { ErrorNotice, Loading } from '../../components/Common';
import { PayoutAccountPanel } from '../../components/PayoutAccountPanel';
import { SettlementPreviewPanel } from '../../components/SettlementPreviewPanel';
import { formatDate, formatDateTime, won } from '../../lib/format';
import { STATEMENT_STATUS_LABEL, settlementPage, settlementPeriodError } from '../../lib/settlements';

export function SellerSettlementsPage() {
  const [params, setParams] = useSearchParams();
  const view = params.get('view') === 'preview' ? 'preview' : 'statements';
  if (params.get('view') === 'account') return <Navigate to="/seller/payout-account" replace />;
  return (
    <main className="content seller-page seller-settlements stack">
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">정산 내역</h1>
          <p>발행된 정산서와 기간별 예상 정산을 확인하세요.</p>
        </div>
      </header>
      <nav className="settlement-tabs" aria-label="정산 메뉴">
        {[
          { value: 'statements', label: '정산서' },
          { value: 'preview', label: '예상 정산' },
        ].map((tab) => (
          <button
            type="button"
            key={tab.value}
            aria-pressed={view === tab.value}
            onClick={() => setParams(tab.value === 'statements' ? {} : { view: tab.value })}
          >
            {tab.label}
          </button>
        ))}
      </nav>
      {view === 'preview' ? (
        <SettlementPreviewPanel key={params.toString()} />
      ) : (
        <>
          <Receivable />
          <Statements key={params.toString()} />
        </>
      )}
    </main>
  );
}
export function SellerPayoutAccountPage() {
  return (
    <main className="content seller-page stack">
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">지급 계좌</h1>
          <p>정산금을 받을 계좌를 등록하고 관리하세요.</p>
        </div>
      </header>
      <PayoutAccountPanel />
    </main>
  );
}
function Receivable() {
  const query = useQuery({
    queryKey: ['seller-receivable', getToken()],
    queryFn: () => api<{ receivable: number; oldestAt: string | null }>('/api/seller/settlements/receivable'),
  });
  return (
    <section className="settlement-receivable">
      <div>
        <h2>미회수 차감</h2>
        <p>아직 정산에서 차감하지 못한 잔액으로, 미지급액과 다른 금액입니다.</p>
        {query.data?.oldestAt && <small>가장 오래된 차감 {formatDateTime(query.data.oldestAt)}</small>}
      </div>
      <div>
        {query.isPending ? (
          <Loading />
        ) : query.isError ? (
          <ErrorNotice error={query.error} />
        ) : (
          <strong>{won(query.data!.receivable)}</strong>
        )}
      </div>
    </section>
  );
}
function Statements() {
  const [params, setParams] = useSearchParams();
  const from = params.get('from') ?? '',
    to = params.get('to') ?? '';
  const status = Object.prototype.hasOwnProperty.call(STATEMENT_STATUS_LABEL, params.get('status') ?? '')
    ? (params.get('status') as StatementStatus)
    : '';
  const page = settlementPage(params.get('page'));
  const [form, setForm] = useState({ from, to, status });
  const [error, setError] = useState<string | null>(null);
  const periodError = settlementPeriodError(from, to);
  const request = new URLSearchParams({ page: String(page), size: '10' });
  if (from) request.set('from', from);
  if (to) request.set('to', to);
  if (status) request.set('status', status);
  const query = useQuery({
    queryKey: ['seller-statements', getToken(), request.toString()],
    enabled: !periodError,
    queryFn: () => api<PageC<StatementSummary>>(`/api/seller/settlements?${request}`),
  });
  const apply = (e: FormEvent) => {
    e.preventDefault();
    const invalid = settlementPeriodError(form.from, form.to);
    setError(invalid);
    if (invalid) return;
    const next = new URLSearchParams();
    if (form.from) next.set('from', form.from);
    if (form.to) next.set('to', form.to);
    if (form.status) next.set('status', form.status);
    setParams(next, { preventScrollReset: true });
  };
  const changePage = (value: number) => {
    const next = new URLSearchParams(params);
    if (value) next.set('page', String(value));
    else next.delete('page');
    setParams(next, { preventScrollReset: true });
  };
  return (
    <section className="panel stack">
      <div className="settlement-panel-heading">
        <div>
          <h2 className="panel-title">정산서 내역</h2>
          <p className="account-hint">발행일 기준 · 최근 발행 순</p>
        </div>
        <button
          className="chip"
          type="button"
          disabled={query.isFetching || !!periodError}
          onClick={() => query.refetch()}
        >
          새로고침
        </button>
      </div>
      <form className="settlement-filters" onSubmit={apply}>
        <div className="field">
          <label htmlFor="statement-from">발행 시작일</label>
          <input
            id="statement-from"
            className="input"
            type="date"
            value={form.from}
            onInput={(e) => setForm({ ...form, from: e.currentTarget.value })}
          />
        </div>
        <div className="field">
          <label htmlFor="statement-to">발행 종료일</label>
          <input
            id="statement-to"
            className="input"
            type="date"
            value={form.to}
            onInput={(e) => setForm({ ...form, to: e.currentTarget.value })}
          />
        </div>
        <div className="field">
          <label htmlFor="statement-status">상태</label>
          <select
            id="statement-status"
            className="input"
            value={form.status}
            onChange={(e) => setForm({ ...form, status: e.target.value as StatementStatus | '' })}
          >
            <option value="">전체</option>
            {Object.entries(STATEMENT_STATUS_LABEL).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>
        <button className="btn btn-ghost">조회</button>
      </form>
      {(error || periodError) && (
        <p className="notice" role="alert">
          {error || periodError}
        </p>
      )}
      {!periodError && query.isPending && <Loading />}
      {query.isError && <ErrorNotice error={query.error} />}
      {query.isError && query.data && (
        <p className="account-hint">최근 조회한 내역입니다. 최신 상태는 다시 확인해 주세요.</p>
      )}
      {query.data && (
        <>
          <p className="account-hint">총 {query.data.totalElements}건</p>
          <div className="seller-table-scroll">
            <table className="seller-table settlement-table">
              <thead>
                <tr>
                  <th>정산서 · 회차</th>
                  <th>발행일</th>
                  <th>판매자 몫</th>
                  <th>차감</th>
                  <th>지급액</th>
                  <th>상태</th>
                </tr>
              </thead>
              <tbody>
                {query.data.content.map((item) => (
                  <tr key={item.statementId}>
                    <td>
                      <Link
                        to={`/seller/settlements/${item.statementId}`}
                        state={{ settlementReturn: `/seller/settlements?${params}` }}
                      >
                        정산서 {item.statementId} ›
                      </Link>
                      <span className="seller-cell-meta">
                        회차 {item.scheduleId} · {item.sequence}차
                      </span>
                    </td>
                    <td>{formatDate(item.issuedAt)}</td>
                    <td>{won(item.saleTotal)}</td>
                    <td>{won(item.offsetTotal)}</td>
                    <td>
                      <strong>{won(item.payableAmount)}</strong>
                    </td>
                    <td>
                      <span className={`seller-state ${item.status === 'PAID' ? 'ready' : 'draft'}`}>
                        {STATEMENT_STATUS_LABEL[item.status]}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {query.data.content.length === 0 && <p className="seller-empty">조건에 맞는 정산서가 없어요.</p>}
          {query.data.totalPages > 1 && (
            <nav className="pager" aria-label="정산서 페이지">
              <button
                className="chip"
                type="button"
                disabled={page === 0 || query.isFetching}
                onClick={() => changePage(page - 1)}
              >
                이전
              </button>
              <span>
                {page + 1} / {query.data.totalPages}
              </span>
              <button
                className="chip"
                type="button"
                disabled={page + 1 >= query.data.totalPages || page >= 10000 || query.isFetching}
                onClick={() => changePage(page + 1)}
              >
                다음
              </button>
            </nav>
          )}
        </>
      )}
    </section>
  );
}
