import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../../api/client';
import type { RefundDetail, RefundOperationOutcome, RefundOperationReason } from '../../api/manageTypes';
import { ErrorNotice, Loading, Title } from '../../components/Common';
import { Done, Facts, Field, Panel } from '../../components/Form';
import { formatDateTime, won } from '../../lib/format';
import { pgObservationLabel, recoveryModeLabel, refundStatusLabel } from '../../lib/labels';
import { newCommandId } from '../../lib/localRecords';

type Action = 'retry' | 'resume';

export function AdminRefundPage() {
  const orderId = useParams().orderId!;
  const path = `/api/admin/payments/${encodeURIComponent(orderId)}/refund`;
  const [refresh, setRefresh] = useState(false);
  const detail = useQuery({
    queryKey: ['admin-refund', orderId, refresh],
    queryFn: () => api<RefundDetail>(`${path}?refresh=${refresh}`),
  });
  // 결과를 모르는 명령은 같은 commandId로 다시 보내야 최초 결과를 받는다. 결과를 받으면 새 키를 쓴다.
  const [commandId, setCommandId] = useState(newCommandId);
  const [reason, setReason] = useState<RefundOperationReason>('ROOT_CAUSE_FIXED');
  const run = useMutation({
    mutationFn: (action: Action) => {
      const current = detail.data!.current;
      return api<RefundOperationOutcome>(`${path}/${action}`, {
        method: 'POST',
        body: {
          commandId,
          reason: action === 'resume' ? 'RESUME_OBSERVATION' : reason,
          expectedStatus: current.status,
          expectedMode: current.mode,
          expectedVersion: current.executionVersion,
          expectedAttempt: current.cancelAttempt,
        },
      });
    },
    onSuccess: () => {
      setCommandId(newCommandId());
      detail.refetch();
    },
    onError: (error) => {
      // PG 결과 불명(503)이나 응답 유실(0)이 아니면 서버가 결과를 저장했으므로 다음 명령은 새 키로 보낸다.
      const unknown = error instanceof ApiError && (error.status === 503 || error.status === 0);
      if (!unknown) setCommandId(newCommandId());
      detail.refetch();
    },
  });

  if (detail.isPending) return <main className="content narrow"><Loading /></main>;
  if (detail.isError) return <main className="content narrow"><ErrorNotice error={detail.error} /></main>;
  const { current, commands, pgObservation, observedAt } = detail.data;
  return (
    <main className="content narrow stack" style={{ gap: 24 }}>
      <Link to="/admin/refunds" className="navlink" style={{ paddingLeft: 0 }}>
        ← 환불 운영
      </Link>
      <Title en="Refund" ko="환불 상세" />
      <Panel title={orderId} note={current.guidance}>
        <Facts
          items={[
            ['환불 상태', refundStatusLabel(current.status)],
            ['복구 모드', recoveryModeLabel(current.mode)],
            ['결제 상태', current.paymentStatus],
            ['PG · 사유', `${current.provider} · ${current.refundReason}`],
            ['금액', won(current.amount)],
            ['실행 version · 취소 시도', `${current.executionVersion} · ${current.cancelAttempt}`],
            ['자동 조회 · 자동 취소', `${current.automaticallyQueryable ? '가능' : '불가'} · ${current.automaticCancelAllowed ? '가능' : '불가'}`],
            ['제한 사유', current.restrictionReason],
            ['마지막 오류', current.lastError],
            ['실패 코드', current.failureCode],
            ['다음 시도', current.nextAttemptAt && formatDateTime(current.nextAttemptAt)],
          ]}
        />
      </Panel>
      <Panel title="PG 조회" note="켜면 PG에 읽기 전용 조회를 보내요. 끄면 저장된 상태만 보여요.">
        <Facts items={[['PG 관찰', pgObservationLabel(pgObservation)], ['관찰 시각', observedAt && formatDateTime(observedAt)]]} />
        <button type="button" className="btn btn-ghost" disabled={detail.isFetching} onClick={() => (refresh ? detail.refetch() : setRefresh(true))}>
          PG에 지금 조회하기
        </button>
      </Panel>
      <Panel
        title="재시도·재개"
        note="재시도는 원인을 고친 뒤 PG 조회로 같은 거래의 승인 전액이 그대로일 때만 실행을 예약해요. 재개는 조회 전용으로 되돌려요. 둘 다 PG 취소나 계좌 반영 완료를 뜻하지 않아요."
      >
        {run.isError && <ErrorNotice error={run.error} />}
        {run.isSuccess && (
          <Done>
            {run.data.result}: {run.data.explanation} (상태 {refundStatusLabel(run.data.status)}, 모드 {recoveryModeLabel(run.data.mode)})
          </Done>
        )}
        <Field id="refund-reason" label="재시도 사유">
          <select id="refund-reason" className="input" value={reason} onChange={(e) => setReason(e.target.value as RefundOperationReason)}>
            <option value="ROOT_CAUSE_FIXED">ROOT_CAUSE_FIXED · 거래·금액 불일치, 업무 거절 원인 해결</option>
            <option value="AUTH_CONFIGURATION_FIXED">AUTH_CONFIGURATION_FIXED · PG 인증 설정 해결</option>
          </select>
        </Field>
        <p className="hint mono">commandId {commandId}</p>
        <div className="actions">
          <button type="button" className="btn btn-primary" disabled={run.isPending} onClick={() => run.mutate('retry')}>
            재시도
          </button>
          <button type="button" className="btn btn-ghost" disabled={run.isPending} onClick={() => run.mutate('resume')}>
            재개(조회 전용)
          </button>
        </div>
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
                {formatDateTime(command.processedAt)} · {command.actor} · {command.reason} · {command.explanation}
              </span>
            </span>
          </div>
        ))}
      </Panel>
    </main>
  );
}
