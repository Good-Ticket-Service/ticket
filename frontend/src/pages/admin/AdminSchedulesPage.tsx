import { useMutation, useQuery } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { api, ifMatch } from '../../api/client';
import type { PublicSchedule, SalesSuspensionResult } from '../../api/manageTypes';
import type { Schedule } from '../../api/types';
import { ErrorNotice, Loading, Title } from '../../components/Common';
import { Done, Facts, Field, Panel } from '../../components/Form';
import { formatDateTime } from '../../lib/format';
import { operationLabel, salesStatusLabel, seatSetupLabel } from '../../lib/labels';

/** MASTER는 회차 version을 읽을 API가 없어, 성공 응답의 version을 회차별로 기억해 다음 요청에 쓴다. */
const VERSIONS = 'ticket.admin.scheduleVersions';

function knownVersion(scheduleId: string): string {
  try {
    return (JSON.parse(localStorage.getItem(VERSIONS) ?? '{}') as Record<string, number>)[scheduleId]?.toString() ?? '';
  } catch {
    return '';
  }
}

function rememberVersion(scheduleId: number, version: number): void {
  try {
    const all = JSON.parse(localStorage.getItem(VERSIONS) ?? '{}') as Record<string, number>;
    localStorage.setItem(VERSIONS, JSON.stringify({ ...all, [scheduleId]: version }));
  } catch {
    // 기억하지 못하면 다음에 직접 입력한다.
  }
}

export function AdminSchedulesPage() {
  const [performanceInput, setPerformanceInput] = useState('');
  const [performanceId, setPerformanceId] = useState('');
  const [scheduleInput, setScheduleInput] = useState('');
  const [scheduleId, setScheduleId] = useState('');
  const [version, setVersion] = useState('');
  const [reason, setReason] = useState('');
  // 회차 ID는 공연 상세 주소에 없어서, 공개 회차 목록으로 공연에서 회차를 고르게 한다.
  const schedules = useQuery({
    queryKey: ['schedules', performanceId],
    enabled: performanceId !== '',
    queryFn: () => api<{ schedules: Schedule[] }>(`/api/performances/${performanceId}/schedules`),
  });
  const schedule = useQuery({
    queryKey: ['schedule', scheduleId],
    enabled: scheduleId !== '',
    queryFn: () => api<PublicSchedule>(`/api/schedules/${scheduleId}`),
  });
  const change = useMutation({
    mutationFn: (suspended: boolean) =>
      api<SalesSuspensionResult>(`/api/admin/schedules/${scheduleId}/sales-suspension`, {
        method: 'PUT',
        headers: ifMatch(Number(version)),
        // 재개할 때 사유를 보내면 400이라 null로 보낸다.
        body: { suspended, reason: suspended ? reason : null },
      }),
    onSuccess: (result) => {
      rememberVersion(result.scheduleId, result.version);
      setVersion(String(result.version));
      schedule.refetch();
    },
  });
  const open = (id: string) => {
    setScheduleId(id);
    setScheduleInput(id);
    setVersion(knownVersion(id));
    change.reset();
  };
  const item = schedule.data;
  return (
    <main className="content narrow stack" style={{ gap: 24 }}>
      <Title en="Schedules" ko="회차 판매 중지" />
      <Panel title="공연에서 회차 고르기" note="공연 ID는 공연 상세 주소(/performances/공연 ID)에 있어요. 공개된 공연만 찾을 수 있어요.">
        <form
          className="actions"
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            setPerformanceId(performanceInput);
          }}
        >
          <input
            className="input"
            style={{ flex: '1 1 160px', width: 'auto' }}
            type="text"
            inputMode="numeric"
            pattern="\d+"
            required
            placeholder="공연 ID"
            aria-label="공연 ID"
            value={performanceInput}
            onChange={(e) => setPerformanceInput(e.target.value)}
          />
          <button className="btn btn-ghost">회차 보기</button>
        </form>
        {schedules.isFetching && !schedules.data && <Loading />}
        {schedules.isError && <ErrorNotice error={schedules.error} />}
        {schedules.data?.schedules.length === 0 && <p className="muted">이 공연에 회차가 없어요.</p>}
        {schedules.data?.schedules.map((s) => (
          <button
            key={s.id}
            type="button"
            className="schedule"
            aria-pressed={String(s.id) === scheduleId}
            onClick={() => open(String(s.id))}
          >
            <span>
              <span className="when num">{formatDateTime(s.startAt)}</span>
              <br />
              <span className="sub num">회차 #{s.id}</span>
            </span>
            <span className={s.salesSuspended ? 'pill on' : 'pill'}>{s.salesSuspended ? '판매 중지됨' : salesStatusLabel(s.salesStatus)}</span>
          </button>
        ))}
        <form
          className="actions"
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            open(scheduleInput);
          }}
        >
          <input
            className="input"
            style={{ flex: '1 1 160px', width: 'auto' }}
            type="text"
            inputMode="numeric"
            pattern="\d+"
            required
            placeholder="회차 ID를 알면 바로 열기"
            aria-label="회차 ID"
            value={scheduleInput}
            onChange={(e) => setScheduleInput(e.target.value)}
          />
          <button className="btn btn-ghost">열기</button>
        </form>
      </Panel>
      {schedule.isFetching && !item && <Loading />}
      {schedule.isError && <ErrorNotice error={schedule.error} />}
      {item && (
        <Panel title={`회차 #${item.id}`}>
          <Facts
            items={[
              ['공연 ID', item.performanceId],
              ['시작', formatDateTime(item.startAt)],
              ['예매 기간', `${formatDateTime(item.bookingOpenAt)} ~ ${formatDateTime(item.bookingCloseAt)}`],
              ['판매 상태', salesStatusLabel(item.salesStatus)],
              ['운영 상태', operationLabel(item.operationStatus)],
              ['좌석 준비', seatSetupLabel(item.seatSetupStatus)],
              ['판매 중지', item.salesSuspended ? '중지됨' : '아니요'],
            ]}
          />
          <form
            className="stack"
            onSubmit={(event) => {
              event.preventDefault();
              change.mutate(!item.salesSuspended);
            }}
          >
            {change.isError && <ErrorNotice error={change.error} />}
            {change.isSuccess && <Done>{change.data.salesSuspended ? '판매를 중지했어요.' : '판매를 재개했어요.'} 새 version은 {change.data.version}이에요.</Done>}
            <Field
              id="schedule-version"
              label="회차 version"
              hint="관리자가 회차 version을 읽을 API가 없어요. 판매자 회차 화면의 version을 받거나, 이 화면에서 바꾼 적이 있으면 자동으로 채워져요."
            >
              <input id="schedule-version" className="input" type="number" min={0} required value={version} onChange={(e) => setVersion(e.target.value)} />
            </Field>
            {!item.salesSuspended && (
              <Field id="schedule-reason" label="중지 사유 (1~500자)">
                <textarea id="schedule-reason" className="input" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
              </Field>
            )}
            <button className="btn btn-primary" disabled={change.isPending}>
              {item.salesSuspended ? '판매 재개' : '판매 중지'}
            </button>
          </form>
        </Panel>
      )}
    </main>
  );
}
