import { useMutation, useQuery } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../../api/client';
import type { PayoutDetail, PayoutRetryOutcome } from '../../api/manageTypes';
import { ErrorNotice, Loading, Title } from '../../components/Common';
import { Done, Facts, Field, Panel } from '../../components/Form';
import { formatDateTime, won } from '../../lib/format';
import { payoutAttentionLabel, payoutStatusLabel } from '../../lib/labels';
import { newCommandId } from '../../lib/localRecords';

export function AdminPayoutPage() {
  const { payoutId } = useParams();
  const path = `/api/admin/settlements/payouts/${payoutId}`;
  const detail = useQuery({ queryKey: ['admin-payout', payoutId], queryFn: () => api<PayoutDetail>(path) });
  // 응답을 못 받았을 때만 같은 commandId로 다시 보낸다. 결과를 받으면 새 키를 쓴다.
  const [commandId, setCommandId] = useState(newCommandId);
  const [note, setNote] = useState('');
  const retry = useMutation({
    mutationFn: () => {
      const current = detail.data!.current;
      return api<PayoutRetryOutcome>(`${path}/retry`, {
        method: 'POST',
        body: { commandId, note, expectedVersion: current.version, expectedAttempt: current.attempt },
      });
    },
    onSuccess: () => {
      setCommandId(newCommandId());
      detail.refetch();
    },
    onError: (error) => {
      if (!(error instanceof ApiError && error.status === 0)) setCommandId(newCommandId());
      detail.refetch();
    },
  });
  if (detail.isPending) return <main className="content narrow"><Loading /></main>;
  if (detail.isError) return <main className="content narrow"><ErrorNotice error={detail.error} /></main>;
  const { current, commands } = detail.data;
  const submit = (event: FormEvent) => {
    event.preventDefault();
    retry.mutate();
  };
  return (
    <main className="content narrow stack" style={{ gap: 24 }}>
      <Link to="/admin/payouts" className="navlink" style={{ paddingLeft: 0 }}>
        ← 지급 운영
      </Link>
      <Title en="Payout" ko={`지급 #${current.payoutId}`} />
      <Panel title={won(current.amount)}>
        <Facts
          items={[
            ['상태', payoutStatusLabel(current.status)],
            ['주의', payoutAttentionLabel(current.attention)],
            ['정산서 · 판매자', `#${current.statementId} · #${current.sellerId}`],
            ['시도 · version', `${current.attempt} · ${current.version}`],
            ['지급대행 상태', current.providerStatus],
            ['조회만 함', current.inquiryOnly ? '예' : '아니요'],
            ['확인 사유', current.verificationReason],
            ['실패 코드', current.failureCode],
            ['마지막 오류', current.lastError],
            ['계좌', current.bankCode && `${current.bankCode} · ${current.holderMasked ?? ''}`],
            ['요청 · 완료', `${current.requestedAt ? formatDateTime(current.requestedAt) : '-'} · ${current.doneAt ? formatDateTime(current.doneAt) : '-'}`],
            ['다음 시도', current.nextAttemptAt && formatDateTime(current.nextAttemptAt)],
          ]}
        />
      </Panel>
      <Panel
        title="재시도"
        note="지급대행이 실패를 확정한 FAILED(돈이 나가지 않음)만 READY로 되돌려요. 결과 불명이나 조회 전용, 완료된 지급은 이중 지급을 막기 위해 거절돼요."
      >
        <form className="stack" onSubmit={submit}>
          {retry.isError && <ErrorNotice error={retry.error} />}
          {retry.isSuccess && (
            <Done>
              {retry.data.result}: {retry.data.explanation}
            </Done>
          )}
          <Field id="payout-note" label="메모 (1~200자)">
            <input id="payout-note" className="input" required maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} />
          </Field>
          <p className="hint mono">commandId {commandId}</p>
          <button className="btn btn-primary" disabled={retry.isPending || current.status !== 'FAILED'}>
            {current.status === 'FAILED' ? '재시도' : '지급 실패(FAILED)일 때만 재시도할 수 있어요'}
          </button>
        </form>
      </Panel>
      <Panel title="명령 기록" note="최근 20개">
        {commands.length === 0 && <p className="muted">기록이 없어요.</p>}
        {commands.map((command) => (
          <div key={command.commandId} className="item">
            <span>
              <span className="name">
                {command.action} · {command.result}
              </span>
              <br />
              <span className="sub">
                {formatDateTime(command.processedAt)} · {command.actor} · {command.note} · {command.explanation}
              </span>
            </span>
          </div>
        ))}
      </Panel>
    </main>
  );
}
