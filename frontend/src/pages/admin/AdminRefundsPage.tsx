import { useInfiniteQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import type { CursorPage, RefundOperationView, RefundRecoveryMode, RefundStatus } from '../../api/manageTypes';
import { ErrorNotice, Loading, Title } from '../../components/Common';
import { Field } from '../../components/Form';
import { formatDateTime, won } from '../../lib/format';

const STATUSES: RefundStatus[] = ['WAITING', 'REQUESTED', 'FAILED'];
const MODES: RefundRecoveryMode[] = ['ACTIVE', 'SLOW', 'VERIFY_ONLY', 'PAUSED'];

/** 미완료 환불만 온다. after/nextCursor는 환불 ID다. */
export function AdminRefundsPage() {
  const [filter, setFilter] = useState({ status: '', mode: '', provider: '', olderThanMinutes: '' });
  const params = new URLSearchParams({ limit: '20' });
  Object.entries(filter).forEach(([key, value]) => value && params.set(key, value));
  const list = useInfiniteQuery({
    queryKey: ['admin-refunds', params.toString()],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => api<CursorPage<RefundOperationView>>(`/api/admin/payments/refunds?${params}&after=${pageParam}`),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const items = list.data?.pages.flatMap((page) => page.items) ?? [];
  const select = (key: keyof typeof filter, options: string[], label: string) => (
    <Field id={`refund-${key}`} label={label}>
      <select id={`refund-${key}`} className="input" value={filter[key]} onChange={(e) => setFilter({ ...filter, [key]: e.target.value })}>
        <option value="">전체</option>
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    </Field>
  );
  return (
    <main className="content medium stack" style={{ gap: 20 }}>
      <Title en="Refunds" ko="환불 운영" />
      <p className="muted">끝나지 않은 환불만 보여요. 주문을 누르면 PG 조회와 재시도·재개를 할 수 있어요.</p>
      <div className="form-grid">
        {select('status', STATUSES, '상태')}
        {select('mode', MODES, '복구 모드')}
        {select('provider', ['TOSS', 'KAKAO'], 'PG')}
        <Field id="refund-olderThanMinutes" label="몇 분 넘게 머문 것만">
          <input
            id="refund-olderThanMinutes"
            className="input"
            type="number"
            min={0}
            value={filter.olderThanMinutes}
            onChange={(e) => setFilter({ ...filter, olderThanMinutes: e.target.value })}
          />
        </Field>
      </div>
      {list.isPending && <Loading />}
      {list.isError && <ErrorNotice error={list.error} />}
      {list.isSuccess && items.length === 0 && <p className="empty">처리할 환불이 없어요.</p>}
      <div className="stack" style={{ gap: 10 }}>
        {items.map((item) => (
          <Link key={item.refundId} to={`/admin/refunds/${encodeURIComponent(item.orderId)}`} className="item">
            <span>
              <span className="name mono">{item.orderId}</span>
              <br />
              <span className="sub num">
                {item.provider} · {item.refundReason} · {won(item.amount)}
                {item.requestedAt && ` · 요청 ${formatDateTime(item.requestedAt)}`}
              </span>
            </span>
            <span className="actions">
              <span className="pill">{item.status}</span>
              <span className={item.mode === 'ACTIVE' ? 'pill' : 'pill on'}>{item.mode}</span>
            </span>
          </Link>
        ))}
      </div>
      {list.hasNextPage && (
        <button type="button" className="btn btn-ghost" disabled={list.isFetchingNextPage} onClick={() => list.fetchNextPage()}>
          더 보기
        </button>
      )}
    </main>
  );
}
