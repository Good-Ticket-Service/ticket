import { useInfiniteQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, getToken } from '../../api/client';
import type { CursorPage, RefundOperationView } from '../../api/manageTypes';
import { ErrorNotice, Loading } from '../../components/Common';
import { Field } from '../../components/Form';
import { formatDateTime, won } from '../../lib/format';
import { recoveryModeLabel, refundStatusLabel } from '../../lib/labels';
import { operationMinutes } from '../../lib/operationCommand';

export function AdminRefundsPage() {
  const [params, setParams] = useSearchParams();
  const [validation, setValidation] = useState('');
  const request = new URLSearchParams({ limit: '20' });
  for (const key of ['status', 'mode', 'provider', 'olderThanMinutes']) {
    const value = params.get(key);
    if (value) request.set(key, value);
  }
  const list = useInfiniteQuery({
    queryKey: ['admin-refunds', getToken(), request.toString()],
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      api<CursorPage<RefundOperationView>>(`/api/admin/payments/refunds?${request}&after=${pageParam}`),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
  const items = list.data?.pages.flatMap((page) => page.items) ?? [];
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const next = new URLSearchParams();
    const data = new FormData(e.currentTarget);
    try {
      for (const key of ['status', 'mode', 'provider', 'olderThanMinutes']) {
        const value = String(data.get(key) ?? '');
        if (value) next.set(key, key === 'olderThanMinutes' ? operationMinutes(value) : value);
      }
      setValidation('');
      setParams(next);
    } catch (error) {
      setValidation((error as Error).message);
    }
  }
  return (
    <main className="content seller-page admin-page stack">
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">환불 운영</h1>
          <p>미완료 환불의 처리 상태와 복구 이력을 확인하세요.</p>
        </div>
        <button className="btn btn-ghost" disabled={list.isFetching} onClick={() => list.refetch()}>
          새로고침
        </button>
      </header>
      <section className="panel stack">
        <form key={params.toString()} onSubmit={submit} className="operation-filters">
          <Field id="refund-status" label="환불 상태">
            <select
              className="input"
              id="refund-status"
              name="status"
              defaultValue={params.get('status') ?? ''}
            >
              <option value="">미완료 전체</option>
              {['WAITING', 'REQUESTED', 'FAILED'].map((v) => (
                <option key={v} value={v}>
                  {refundStatusLabel(v)}
                </option>
              ))}
            </select>
          </Field>
          <Field id="refund-mode" label="복구 모드">
            <select className="input" id="refund-mode" name="mode" defaultValue={params.get('mode') ?? ''}>
              <option value="">전체</option>
              {['ACTIVE', 'SLOW', 'VERIFY_ONLY', 'PAUSED'].map((v) => (
                <option key={v} value={v}>
                  {recoveryModeLabel(v)}
                </option>
              ))}
            </select>
          </Field>
          <Field id="refund-provider" label="PG">
            <select
              className="input"
              id="refund-provider"
              name="provider"
              defaultValue={params.get('provider') ?? ''}
            >
              <option value="">전체</option>
              <option>TOSS</option>
              <option>KAKAO</option>
            </select>
          </Field>
          <Field id="refund-age" label="경과 시간 (분 이상)">
            <input
              className="input"
              id="refund-age"
              name="olderThanMinutes"
              type="number"
              min="0"
              max="5256000"
              step="1"
              defaultValue={params.get('olderThanMinutes') ?? ''}
              placeholder="전체 기간"
            />
          </Field>
          <button className="btn btn-primary">조회</button>
          <button
            type="button"
            className="btn btn-ghost"
            onClick={(event) => {
              event.currentTarget.form?.reset();
              setValidation('');
              setParams({});
            }}
          >
            초기화
          </button>
        </form>
        {validation && (
          <p role="alert" className="notice">
            {validation}
          </p>
        )}
      </section>
      <section className="panel stack">
        <div className="operation-panel-heading">
          <h2 className="panel-title">미완료 환불</h2>
          <span>불러온 {items.length}건 · 전체 집계 아님</span>
        </div>
        <p className="account-hint">PG 취소 완료와 고객 카드·계좌 반영 시점은 다를 수 있습니다.</p>
        {list.isPending && <Loading />}
        {list.isError && <ErrorNotice error={list.error} />}
        {list.data && list.isError && (
          <p className="notice">이전 조회 결과입니다. 새로고침 후 최신 상태를 확인하세요.</p>
        )}
        {list.isSuccess && !items.length && <p className="seller-empty">조건에 맞는 미완료 환불이 없어요.</p>}
        <div className="operation-list">
          {items.map((item) => (
            <Link
              key={item.refundId}
              className="operation-row"
              to={`/admin/refunds/${encodeURIComponent(item.orderId)}`}
              state={{ operationReturn: '/admin/refunds?' + params }}
            >
              <span className="operation-symbol" aria-hidden="true">
                ↶
              </span>
              <div className="operation-row-main">
                <strong>{item.orderId}</strong>
                <span>
                  {item.provider} · {item.refundReason}
                </span>
                <small>
                  {item.requestedAt ? '요청 ' + formatDateTime(item.requestedAt) : '환불 요청 대기'}
                </small>
              </div>
              <div className="operation-row-state">
                <span className="pill">{refundStatusLabel(item.status)}</span>
                <span className={item.mode === 'ACTIVE' ? 'pill' : 'pill on'}>
                  {recoveryModeLabel(item.mode)}
                </span>
              </div>
              <strong className="operation-amount">{won(item.amount)}</strong>
              <span className="operation-chevron" aria-hidden="true">
                ›
              </span>
            </Link>
          ))}
        </div>
        {list.hasNextPage && (
          <button className="btn btn-ghost" disabled={list.isFetching} onClick={() => list.fetchNextPage()}>
            {list.isFetchingNextPage ? '불러오는 중' : '더 보기'}
          </button>
        )}
      </section>
    </main>
  );
}
