import { useInfiniteQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { api, getToken } from '../../api/client';
import type { CursorPage, PayoutView, SellerReceivable } from '../../api/manageTypes';
import { ErrorNotice, Loading } from '../../components/Common';
import { Field } from '../../components/Form';
import { formatDateTime, won } from '../../lib/format';
import { payoutAttentionLabel, payoutStatusLabel } from '../../lib/labels';
const ATTENTIONS = [
  'ACCOUNT_MISSING',
  'GATE_BLOCKED',
  'READY_UNSCHEDULED',
  'READY',
  'RESULT_UNKNOWN',
  'INQUIRY_ONLY',
  'IN_PROGRESS',
  'FAILED',
  'DONE',
];
export function AdminPayoutsPage() {
  const [params, setParams] = useSearchParams();
  const receivables = params.get('view') === 'receivables';
  const request = new URLSearchParams({ limit: '20' });
  for (const key of ['status', 'attention']) {
    const v = params.get(key);
    if (v) request.set(key, v);
  }
  const list = useInfiniteQuery({
    queryKey: ['admin-payouts', getToken(), request.toString()],
    enabled: !receivables,
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      api<CursorPage<PayoutView>>(`/api/admin/settlements/payouts?${request}&after=${pageParam}`),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const owed = useInfiniteQuery({
    queryKey: ['admin-receivables', getToken()],
    enabled: receivables,
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      api<CursorPage<SellerReceivable>>(`/api/admin/settlements/receivables?limit=20&after=${pageParam}`),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const query = receivables ? owed : list;
  const items = list.data?.pages.flatMap((p) => p.items) ?? [];
  const sellers = owed.data?.pages.flatMap((p) => p.items) ?? [];
  const change = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next);
  };
  return (
    <main className="content seller-page admin-page stack">
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">지급 운영</h1>
          <p>판매자 지급 상태와 미회수 차감을 확인하세요.</p>
        </div>
        <button className="btn btn-ghost" disabled={query.isFetching} onClick={() => query.refetch()}>
          새로고침
        </button>
      </header>
      <nav className="settlement-tabs" aria-label="지급 운영 구분">
        <button aria-pressed={!receivables} onClick={() => change('view', '')}>
          판매자 지급
        </button>
        <button aria-pressed={receivables} onClick={() => change('view', 'receivables')}>
          미회수 차감
        </button>
      </nav>
      <section className="panel stack">
        {!receivables && (
          <div className="operation-filters">
            <Field id="payout-status" label="지급 상태">
              <select
                id="payout-status"
                className="input"
                value={params.get('status') ?? ''}
                onChange={(e) => change('status', e.target.value)}
              >
                <option value="">미완료 전체</option>
                {['READY', 'REQUESTED', 'FAILED', 'DONE'].map((v) => (
                  <option key={v} value={v}>
                    {payoutStatusLabel(v)}
                  </option>
                ))}
              </select>
            </Field>
            <Field id="payout-attention" label="확인 항목">
              <select
                id="payout-attention"
                className="input"
                value={params.get('attention') ?? ''}
                onChange={(e) => change('attention', e.target.value)}
              >
                <option value="">전체</option>
                {ATTENTIONS.map((v) => (
                  <option key={v} value={v}>
                    {payoutAttentionLabel(v)}
                  </option>
                ))}
              </select>
            </Field>
            <button className="btn btn-ghost" onClick={() => setParams({})}>
              초기화
            </button>
          </div>
        )}
        <div className="operation-panel-heading">
          <h2 className="panel-title">{receivables ? '미회수 차감' : '판매자 지급'}</h2>
          <span>불러온 {receivables ? sellers.length : items.length}건 · 전체 집계 아님</span>
        </div>
        <p className="account-hint">
          {receivables
            ? '아직 상계하지 못한 차감이 있는 판매자만 표시합니다.'
            : '필터를 선택하지 않으면 지급 완료 건을 제외합니다. 결과 불명 건은 재지급할 수 없습니다.'}
        </p>
        {query.isPending && <Loading />}
        {query.isError && <ErrorNotice error={query.error} />}
        {query.data && query.isError && (
          <p className="notice">이전 조회 결과입니다. 최신 상태를 다시 확인하세요.</p>
        )}
        {query.isSuccess && !(receivables ? sellers : items).length && (
          <p className="seller-empty">
            {receivables ? '미회수 차감이 있는 판매자가 없어요.' : '조건에 맞는 지급이 없어요.'}
          </p>
        )}
        <div className="operation-list">
          {receivables
            ? sellers.map((s) => (
                <article className="operation-row" key={s.sellerId}>
                  <span className="operation-symbol" aria-hidden="true">
                    −
                  </span>
                  <div className="operation-row-main">
                    <strong>판매자 {s.sellerId}</strong>
                    <span>미회수 {s.openCount}건</span>
                    <small>가장 오래된 차감 {formatDateTime(s.oldestAt)}</small>
                  </div>
                  <strong className="operation-amount">{won(s.receivable)}</strong>
                </article>
              ))
            : items.map((item) => (
                <Link
                  key={item.payoutId}
                  className="operation-row"
                  to={`/admin/payouts/${item.payoutId}`}
                  state={{ operationReturn: '/admin/payouts?' + params }}
                >
                  <span className="operation-symbol" aria-hidden="true">
                    ↗
                  </span>
                  <div className="operation-row-main">
                    <strong>
                      지급 {item.payoutId} · 판매자 {item.sellerId}
                    </strong>
                    <span>
                      정산서 {item.statementId} · 시도 {item.attempt}
                    </span>
                    <small>{item.requestedAt ? formatDateTime(item.requestedAt) : '지급 요청 전'}</small>
                  </div>
                  <div className="operation-row-state">
                    <span className="pill">{payoutStatusLabel(item.status)}</span>
                    {item.attention !== item.status && (
                      <span className="pill on">{payoutAttentionLabel(item.attention)}</span>
                    )}
                  </div>
                  <strong className="operation-amount">{won(item.amount)}</strong>
                  <span className="operation-chevron" aria-hidden="true">
                    ›
                  </span>
                </Link>
              ))}
        </div>
        {query.hasNextPage && (
          <button className="btn btn-ghost" disabled={query.isFetching} onClick={() => query.fetchNextPage()}>
            {query.isFetchingNextPage ? '불러오는 중' : '더 보기'}
          </button>
        )}
      </section>
    </main>
  );
}
