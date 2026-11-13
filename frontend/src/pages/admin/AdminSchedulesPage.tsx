import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, getToken, ifMatch } from '../../api/client';
import type { PublicSchedule, SalesSuspensionResult } from '../../api/manageTypes';
import type { Schedule } from '../../api/types';
import { ErrorNotice, Loading } from '../../components/Common';
import { formatDateTime } from '../../lib/format';
import { operationLabel, salesStatusLabel, seatSetupLabel } from '../../lib/labels';
const VERSIONS = 'ticket.admin.scheduleVersions';
function knownVersion(id: string) {
  try {
    return (
      (JSON.parse(localStorage.getItem(VERSIONS) ?? '{}') as Record<string, number>)[id]?.toString() ?? ''
    );
  } catch {
    return '';
  }
}
function rememberVersion(id: number, version: number) {
  try {
    const all = JSON.parse(localStorage.getItem(VERSIONS) ?? '{}');
    localStorage.setItem(VERSIONS, JSON.stringify({ ...all, [id]: version }));
  } catch {
    /* 브라우저 저장소를 사용할 수 없으면 직접 입력한다. */
  }
}
const validId = (id: string) => /^[1-9]\d*$/.test(id) && Number.isSafeInteger(Number(id));
export function AdminSchedulesPage() {
  const [params, setParams] = useSearchParams();
  const performanceId = validId(params.get('performanceId') ?? '') ? params.get('performanceId')! : '',
    scheduleId = validId(params.get('scheduleId') ?? '') ? params.get('scheduleId')! : '';
  const [performanceInput, setPerformanceInput] = useState(performanceId),
    [scheduleInput, setScheduleInput] = useState(scheduleId);
  const schedules = useQuery({
    queryKey: ['schedules', performanceId],
    enabled: !!performanceId,
    queryFn: () => api<{ schedules: Schedule[] }>(`/api/performances/${performanceId}/schedules`),
  });
  const open = (id: string) => {
    if (!validId(id)) return;
    const next = new URLSearchParams(params);
    next.set('scheduleId', id);
    setScheduleInput(id);
    setParams(next, { preventScrollReset: true });
  };
  return (
    <main className="content seller-page admin-page stack">
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">회차 운영</h1>
          <p>회차의 판매 상태를 조회하고 중지·재개하세요.</p>
        </div>
      </header>
      <div className="admin-schedule-layout">
        <section className="panel stack">
          <h2 className="panel-title">회차 조회</h2>
          <p className="account-hint">
            공연별 조회는 공개된 공연만 지원합니다. 회차 ID를 알면 직접 열 수 있어요.
          </p>
          <form
            className="stack"
            onSubmit={(event: FormEvent) => {
              event.preventDefault();
              if (validId(performanceInput)) setParams({ performanceId: performanceInput });
            }}
          >
            <div className="field">
              <label htmlFor="admin-performance-id">공연 ID</label>
              <input
                id="admin-performance-id"
                className="input"
                type="number"
                min={1}
                max={Number.MAX_SAFE_INTEGER}
                required
                value={performanceInput}
                onChange={(e) => setPerformanceInput(e.target.value)}
              />
            </div>
            <button className="btn btn-ghost">공연의 회차 조회</button>
          </form>
          <form
            className="stack admin-seat-section"
            onSubmit={(event) => {
              event.preventDefault();
              open(scheduleInput);
            }}
          >
            <div className="field">
              <label htmlFor="admin-schedule-id">회차 ID</label>
              <input
                id="admin-schedule-id"
                className="input"
                type="number"
                min={1}
                max={Number.MAX_SAFE_INTEGER}
                required
                value={scheduleInput}
                onChange={(e) => setScheduleInput(e.target.value)}
              />
            </div>
            <button className="btn btn-ghost">회차 열기</button>
          </form>
          {schedules.isPending && performanceId && <Loading />}
          {schedules.isError && <ErrorNotice error={schedules.error} />}
          {schedules.data?.schedules.length === 0 && <p className="account-hint">공개된 회차가 없어요.</p>}
          <div className="admin-schedule-choices">
            {schedules.data?.schedules.map((item) => (
              <button
                type="button"
                key={item.id}
                aria-pressed={String(item.id) === scheduleId}
                onClick={() => open(String(item.id))}
              >
                <strong>{formatDateTime(item.startAt)}</strong>
                <span>
                  회차 {item.id} · {item.salesSuspended ? '판매 중지' : salesStatusLabel(item.salesStatus)}
                </span>
              </button>
            ))}
          </div>
        </section>
        {scheduleId ? (
          <ScheduleOperation key={scheduleId} id={scheduleId} />
        ) : (
          <div className="admin-selection-empty">
            <p>조회할 회차를 선택해 주세요.</p>
          </div>
        )}
      </div>
    </main>
  );
}
function ScheduleOperation({ id }: { id: string }) {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ['admin-public-schedule', getToken(), id],
    queryFn: () => api<PublicSchedule>(`/api/schedules/${id}`),
    retry: false,
  });
  const [version, setVersion] = useState(() => knownVersion(id)),
    [reason, setReason] = useState('');
  const change = useMutation({
    mutationFn: (value: { suspended: boolean; version: number; reason: string | null }) =>
      api<SalesSuspensionResult>(`/api/admin/schedules/${id}/sales-suspension`, {
        method: 'PUT',
        headers: ifMatch(value.version),
        body: { suspended: value.suspended, reason: value.reason },
      }),
    onSuccess: (result) => {
      rememberVersion(result.scheduleId, result.version);
      setVersion(String(result.version));
      setReason('');
      client.invalidateQueries({ queryKey: ['schedules'] });
      query.refetch();
    },
  });
  const item = query.data;
  if (query.isPending)
    return (
      <div className="panel">
        <Loading />
      </div>
    );
  if (!item)
    return (
      <section className="panel stack">
        <ErrorNotice error={query.error} />
        <button type="button" className="btn btn-ghost" onClick={() => query.refetch()}>
          회차 다시 조회
        </button>
      </section>
    );
  const allowed =
    /^\d+$/.test(version) &&
    Number.isSafeInteger(Number(version)) &&
    (item.salesSuspended || !!reason.trim());
  return (
    <section className="panel stack admin-accent-panel">
      <div className="settlement-panel-heading">
        <h2 className="panel-title">회차 {item.id}</h2>
        <button
          className="chip"
          disabled={query.isFetching || change.isPending}
          onClick={() => query.refetch()}
        >
          상태 확인
        </button>
      </div>
      {query.isError && <ErrorNotice error={query.error} />}
      <dl className="settlement-account-facts">
        <div>
          <dt>공연 ID</dt>
          <dd>{item.performanceId}</dd>
        </div>
        <div>
          <dt>공연 시작</dt>
          <dd>{formatDateTime(item.startAt)}</dd>
        </div>
        <div>
          <dt>예매 오픈</dt>
          <dd>{formatDateTime(item.bookingOpenAt)}</dd>
        </div>
        <div>
          <dt>예매 마감</dt>
          <dd>{formatDateTime(item.bookingCloseAt)}</dd>
        </div>
        <div>
          <dt>판매 상태</dt>
          <dd>
            {item.salesSuspended
              ? '판매 중지'
              : item.salesStatus === 'UNAVAILABLE'
                ? '판매 불가'
                : salesStatusLabel(item.salesStatus)}
          </dd>
        </div>
        <div>
          <dt>운영 상태</dt>
          <dd>{operationLabel(item.operationStatus)}</dd>
        </div>
        <div>
          <dt>좌석 준비</dt>
          <dd>{seatSetupLabel(item.seatSetupStatus)}</dd>
        </div>
      </dl>
      <form
        className="stack admin-seat-section"
        onSubmit={(event) => {
          event.preventDefault();
          if (allowed && !change.isPending && !query.isError && !query.isFetching)
            change.mutate({
              suspended: !item.salesSuspended,
              version: Number(version),
              reason: item.salesSuspended ? null : reason.trim(),
            });
        }}
      >
        <fieldset
          className="seller-fieldset stack"
          disabled={change.isPending || query.isFetching || query.isError}
        >
          {change.isError && <ErrorNotice error={change.error} />}{' '}
          {change.isSuccess && (
            <p role="status" className="account-success">
              {change.data.salesSuspended ? '판매를 중지했어요.' : '판매를 재개했어요.'} 새 version은{' '}
              {change.data.version}입니다.
            </p>
          )}
          <div className="field">
            <label htmlFor="schedule-version">회차 version</label>
            <input
              id="schedule-version"
              className="input"
              type="number"
              min={0}
              max={Number.MAX_SAFE_INTEGER}
              required
              value={version}
              onChange={(e) => setVersion(e.target.value)}
            />
            <p className="account-hint">
              관리자용 version 조회 API가 없어 판매자에게 받은 값 또는 이 화면의 변경 성공 값을 사용합니다.
            </p>
          </div>
          {!item.salesSuspended && (
            <div className="field">
              <label htmlFor="schedule-reason">중지 사유</label>
              <textarea
                id="schedule-reason"
                className="input"
                required
                maxLength={500}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
          )}
          <p className="account-hint">
            판매 중지는 신규 좌석 선점을 막습니다. 기존 유효 선점은 만료 전까지 결제할 수 있어요.
          </p>
          <button className="btn btn-primary" disabled={!allowed}>
            {change.isPending ? '처리 중' : item.salesSuspended ? '판매 재개' : '판매 중지'}
          </button>
        </fieldset>
      </form>
    </section>
  );
}
