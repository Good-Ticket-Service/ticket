import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { api, getToken } from '../../api/client';
import type { RefundDetail, RefundOperationOutcome, RefundOperationReason } from '../../api/manageTypes';
import { ErrorNotice, Loading } from '../../components/Common';
import { Done, Facts, Field, Panel } from '../../components/Form';
import { OperationConfirm, OperationHistory, useOperation } from '../../components/Operations';
import { formatDateTime, won } from '../../lib/format';
import {
  pgObservationLabel,
  recoveryModeLabel,
  refundStatusLabel,
  refundGuidanceLabel,
} from '../../lib/labels';
import { newCommandId } from '../../lib/localRecords';
type Action = 'retry' | 'resume';
export function AdminRefundPage() {
  const orderId = useParams().orderId ?? '';
  if (!orderId.trim() || orderId.length > 64)
    return (
      <main className="content seller-page">
        <p className="notice">올바른 주문 ID를 확인하세요.</p>
        <Link to="/admin/refunds">환불 목록</Link>
      </main>
    );
  return <RefundEditor key={orderId} orderId={orderId} />;
}
function RefundEditor({ orderId }: { orderId: string }) {
  const location = useLocation();
  const client = useQueryClient();
  const path = `/api/admin/payments/${encodeURIComponent(orderId)}/refund`;
  const [reason, setReason] = useState<RefundOperationReason>('ROOT_CAUSE_FIXED');
  const [asking, setAsking] = useState<Action | null>(null);
  const detail = useQuery({
    queryKey: ['admin-refund', getToken(), orderId],
    queryFn: () => api<RefundDetail>(`${path}?refresh=false`),
  });
  const observation = useMutation({
    mutationFn: () => api<RefundDetail>(`${path}?refresh=true`),
    onSuccess: (data) => client.setQueryData(['admin-refund', getToken(), orderId], data),
  });
  const run = useOperation<RefundOperationOutcome>(async () => {
    await Promise.all([detail.refetch(), client.invalidateQueries({ queryKey: ['admin-refunds'] })]);
  });
  useEffect(() => {
    if (run.data) setAsking(null);
  }, [run.data]);
  const back =
    typeof location.state?.operationReturn === 'string' &&
    location.state.operationReturn.startsWith('/admin/refunds?')
      ? location.state.operationReturn
      : '/admin/refunds';
  if (detail.isPending)
    return (
      <main className="content seller-page">
        <Loading />
      </main>
    );
  if (!detail.data)
    return (
      <main className="content seller-page stack">
        <Link to={back}>‹ 환불 목록</Link>
        <ErrorNotice error={detail.error} />
        <button className="btn btn-ghost" onClick={() => detail.refetch()}>
          다시 조회
        </button>
      </main>
    );
  const { current: c, commands, pgObservation, observedAt } = detail.data;
  const busy = run.isPending || detail.isFetching || observation.isPending;
  const terminal = c.status === 'DONE' || c.status === 'SKIPPED';
  const open = (action: Action) => {
    run.reset();
    setAsking(action);
  };
  const execute = () =>
    run.execute(`${path}/${asking ?? 'retry'}`, {
      commandId: newCommandId(),
      reason: asking === 'resume' ? 'RESUME_OBSERVATION' : reason,
      expectedStatus: c.status,
      expectedMode: c.mode,
      expectedVersion: c.executionVersion,
      expectedAttempt: c.cancelAttempt,
    });
  return (
    <main className="content seller-page admin-page stack">
      <Link to={back} className="navlink">
        ‹ 환불 목록
      </Link>
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">환불 상세</h1>
          <p className="operation-order">{orderId}</p>
        </div>
        <button className="btn btn-ghost" disabled={busy} onClick={() => detail.refetch()}>
          저장 상태 새로고침
        </button>
      </header>
      {detail.isError && (
        <>
          <ErrorNotice error={detail.error} />
          <p className="notice">이전 조회 결과입니다. 최신 조회에 성공한 뒤 운영 명령을 실행하세요.</p>
        </>
      )}
      <section className="panel operation-summary">
        <div>
          <span className="pill">{refundStatusLabel(c.status)}</span>
          <h2>{won(c.amount)}</h2>
          <p>
            {c.provider} · {c.refundReason}
          </p>
        </div>
        <div>
          <span className={terminal ? 'pill' : 'pill on'}>{terminal ? '복구 작업 종료' : recoveryModeLabel(c.mode)}</span>
          <p>{c.requestedAt ? '요청 ' + formatDateTime(c.requestedAt) : '환불 요청 대기'}</p>
        </div>
      </section>
      <div className="operation-detail-grid">
        <div className="stack">
          <Panel title="처리 정보">
            <Facts
              items={[
                ['결제 상태', c.paymentStatus],
                ['복구 안내', refundGuidanceLabel(c.guidance)],
                ['실행 버전 / 시도', `${c.executionVersion} / ${c.cancelAttempt}`],
                [
                  '자동 조회 / 자동 취소',
                  `${c.automaticallyQueryable ? '가능' : '불가'} / ${c.automaticCancelAllowed ? '가능' : '불가'}`,
                ],
                ['제한 사유', c.restrictionReason],
                ['실패 코드', c.failureCode],
                ['마지막 오류', c.lastError],
                ['다음 시도', c.nextAttemptAt && formatDateTime(c.nextAttemptAt)],
              ]}
            />
          </Panel>
          <Panel
            title="PG 조회"
            note="읽기 전용으로 거래 상태를 확인합니다. 조회 자체가 환불 완료를 확정하지 않습니다."
          >
            <Facts
              items={[
                ['PG 관찰', pgObservationLabel(pgObservation)],
                ['관찰 시각', observedAt && formatDateTime(observedAt)],
              ]}
            />
            {observation.isError && <ErrorNotice error={observation.error} />}
            <button className="btn btn-ghost" disabled={busy} onClick={() => observation.mutate()}>
              PG에 지금 조회하기
            </button>
          </Panel>
        </div>
        <section className="panel stack operation-action-panel">
          <h2 className="panel-title">복구 작업</h2>
          <p className="account-hint">
            원인을 해결한 뒤 요청하세요. 재시도는 서버 검증 후 실행을 예약하며, 재개는 조회 전용입니다. 고객
            카드·계좌 반영 완료를 뜻하지 않습니다.
          </p>
          {run.isError && <ErrorNotice error={run.error} />}
          {run.data && (
            <Done>
              {run.data.result} · {run.data.explanation}
            </Done>
          )}
          {run.uncertain && (
            <p className="notice" role="status">
              요청 결과를 확인하지 못했어요. 아래에서 동일한 명령을 다시 확인하세요. 이 화면을 유지해 주세요.
            </p>
          )}
          <Field id="refund-reason" label="재시도 사유">
            <select
              id="refund-reason"
              className="input"
              value={reason}
              disabled={busy || run.uncertain}
              onChange={(e) => setReason(e.target.value as RefundOperationReason)}
            >
              <option value="ROOT_CAUSE_FIXED">거래·금액·업무 거절 원인 해결</option>
              <option value="AUTH_CONFIGURATION_FIXED">PG 인증 설정 해결</option>
            </select>
          </Field>
          {run.uncertain ? (
            <button className="btn btn-primary" disabled={busy} onClick={execute}>
              같은 명령 결과 확인
            </button>
          ) : (
            <>
              <button
                className="btn btn-primary"
                disabled={busy || detail.isError || terminal}
                onClick={() => open('retry')}
              >
                재시도 요청
              </button>
              <button
                className="btn btn-ghost"
                disabled={busy || detail.isError || terminal}
                onClick={() => open('resume')}
              >
                조회 전용으로 재개
              </button>
            </>
          )}
          {terminal && <p className="account-hint">종료된 환불에는 복구 명령을 실행할 수 없어요.</p>}
        </section>
      </div>
      <OperationHistory commands={commands} />
      {asking && (
        <OperationConfirm
          title={asking === 'retry' ? '환불 재시도를 요청할까요?' : '조회 전용으로 재개할까요?'}
          busy={run.isPending}
          error={run.error}
          onCancel={() => setAsking(null)}
          onConfirm={execute}
        >
          <p className="operation-order">
            {orderId} · {won(c.amount)}
          </p>
          <p>
            {asking === 'retry'
              ? '서버가 PG 거래와 현재 상태를 확인한 뒤 복구 실행을 예약합니다.'
              : '조회 전용 복구를 재개합니다. 새 PG 취소 권한을 부여하지 않습니다.'}
          </p>
        </OperationConfirm>
      )}
    </main>
  );
}
