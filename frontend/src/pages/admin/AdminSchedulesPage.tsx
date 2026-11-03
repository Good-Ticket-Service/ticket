import { useMutation, useQuery } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { api, ifMatch } from '../../api/client';
import type { PublicSchedule, SalesSuspensionResult } from '../../api/manageTypes';
import { ErrorNotice, Loading, Title } from '../../components/Common';
import { Done, Facts, Field, Panel } from '../../components/Form';
import { formatDateTime } from '../../lib/format';

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
  const [input, setInput] = useState('');
  const [scheduleId, setScheduleId] = useState('');
  const [version, setVersion] = useState('');
  const [reason, setReason] = useState('');
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
  const open = (event: FormEvent) => {
    event.preventDefault();
    setScheduleId(input);
    setVersion(knownVersion(input));
    change.reset();
  };
  const item = schedule.data;
  return (
    <main className="content narrow stack" style={{ gap: 24 }}>
      <Title en="Schedules" ko="회차 판매 중지" />
      <Panel title="회차 찾기" note="회차 ID는 판매자에게 받거나 공연 상세 주소에서 확인해요.">
        <form className="actions" onSubmit={open}>
          <input
            className="input"
            style={{ flex: '1 1 160px', width: 'auto' }}
            type="number"
            min={1}
            required
            placeholder="회차 ID"
            aria-label="회차 ID"
            value={input}
            onChange={(e) => setInput(e.target.value)}
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
              ['판매 상태', item.salesStatus],
              ['좌석 준비', item.seatSetupStatus],
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
              hint="관리자가 회차 version을 읽을 API가 없어요. 판매자 회차 조회의 version을 받거나, 이 화면에서 바꾼 적이 있으면 자동으로 채워져요."
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
