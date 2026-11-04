import { useInfiniteQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import type { CursorPage, PayoutAttention, PayoutStatus, PayoutView, SellerReceivable } from '../../api/manageTypes';
import { ErrorNotice, Loading, Title } from '../../components/Common';
import { Field } from '../../components/Form';
import { formatDateTime, won } from '../../lib/format';
import { payoutAttentionLabel, payoutStatusLabel } from '../../lib/labels';

const STATUSES: PayoutStatus[] = ['READY', 'REQUESTED', 'FAILED', 'DONE'];
const ATTENTIONS: PayoutAttention[] = [
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
  const [tab, setTab] = useState<'payouts' | 'receivables'>('payouts');
  return (
    <main className="content medium stack" style={{ gap: 20 }}>
      <Title en="Payouts" ko="지급 운영" />
      <div className="tabs">
        <button type="button" className="tab" aria-pressed={tab === 'payouts'} onClick={() => setTab('payouts')}>
          판매자 지급
        </button>
        <button type="button" className="tab" aria-pressed={tab === 'receivables'} onClick={() => setTab('receivables')}>
          미회수 차감
        </button>
      </div>
      {tab === 'payouts' ? <Payouts /> : <Receivables />}
    </main>
  );
}

/** 상태·주의를 고르지 않으면 DONE을 뺀 미완료 지급만 온다. after/nextCursor는 지급 ID다. */
function Payouts() {
  const [filter, setFilter] = useState({ status: '', attention: '' });
  const params = new URLSearchParams({ limit: '20' });
  Object.entries(filter).forEach(([key, value]) => value && params.set(key, value));
  const list = useInfiniteQuery({
    queryKey: ['admin-payouts', params.toString()],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => api<CursorPage<PayoutView>>(`/api/admin/settlements/payouts?${params}&after=${pageParam}`),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const items = list.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <>
      <div className="form-grid">
        <Field id="payout-status" label="상태">
          <select id="payout-status" className="input" value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value })}>
            <option value="">미완료 전체</option>
            {STATUSES.map((status) => (
              <option key={status} value={status}>
                {payoutStatusLabel(status)}
              </option>
            ))}
          </select>
        </Field>
        <Field id="payout-attention" label="주의">
          <select id="payout-attention" className="input" value={filter.attention} onChange={(e) => setFilter({ ...filter, attention: e.target.value })}>
            <option value="">전체</option>
            {ATTENTIONS.map((attention) => (
              <option key={attention} value={attention}>
                {payoutAttentionLabel(attention)}
              </option>
            ))}
          </select>
        </Field>
      </div>
      {list.isPending && <Loading />}
      {list.isError && <ErrorNotice error={list.error} />}
      {list.isSuccess && items.length === 0 && <p className="empty">해당하는 지급이 없어요.</p>}
      <div className="stack" style={{ gap: 10 }}>
        {items.map((item) => (
          <Link key={item.payoutId} to={`/admin/payouts/${item.payoutId}`} className="item">
            <span>
              <span className="name num">
                지급 #{item.payoutId} · {won(item.amount)}
              </span>
              <br />
              <span className="sub num">
                정산서 #{item.statementId} · 판매자 #{item.sellerId} · 시도 {item.attempt}
                {item.requestedAt && ` · 요청 ${formatDateTime(item.requestedAt)}`}
              </span>
            </span>
            <span className="actions">
              <span className="pill">{payoutStatusLabel(item.status)}</span>
              {/* 주의가 상태와 같으면 더 볼 것이 없고, 다르면(계좌 미등록·결과 모름 등) 강조한다 */}
              <span className={item.attention === item.status ? 'pill' : 'pill on'}>{payoutAttentionLabel(item.attention)}</span>
            </span>
          </Link>
        ))}
      </div>
      {list.hasNextPage && (
        <button type="button" className="btn btn-ghost" disabled={list.isFetchingNextPage} onClick={() => list.fetchNextPage()}>
          더 보기
        </button>
      )}
    </>
  );
}

/** 아직 상계하지 못한 차감이 있는 판매자. after/nextCursor는 판매자 ID다. */
function Receivables() {
  const list = useInfiniteQuery({
    queryKey: ['admin-receivables'],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => api<CursorPage<SellerReceivable>>(`/api/admin/settlements/receivables?limit=20&after=${pageParam}`),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const items = list.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <>
      {list.isPending && <Loading />}
      {list.isError && <ErrorNotice error={list.error} />}
      {list.isSuccess && items.length === 0 && <p className="empty">미회수 차감이 있는 판매자가 없어요.</p>}
      <div className="stack" style={{ gap: 10 }}>
        {items.map((item) => (
          <div key={item.sellerId} className="item">
            <span>
              <span className="name num">판매자 #{item.sellerId}</span>
              <br />
              <span className="sub num">
                미회수 {item.openCount}건 · 가장 오래된 차감 {formatDateTime(item.oldestAt)}
              </span>
            </span>
            <span className="name num">{won(item.receivable)}</span>
          </div>
        ))}
      </div>
      {list.hasNextPage && (
        <button type="button" className="btn btn-ghost" disabled={list.isFetchingNextPage} onClick={() => list.fetchNextPage()}>
          더 보기
        </button>
      )}
    </>
  );
}
