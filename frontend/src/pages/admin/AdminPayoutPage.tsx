import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { api, getToken } from '../../api/client';
import type { PayoutDetail, PayoutRetryOutcome } from '../../api/manageTypes';
import { ErrorNotice, Loading } from '../../components/Common';
import { Done, Facts, Field, Panel } from '../../components/Form';
import { OperationConfirm, OperationHistory, useOperation } from '../../components/Operations';
import { formatDateTime, won } from '../../lib/format';
import { payoutAttentionLabel, payoutStatusLabel } from '../../lib/labels';
import { newCommandId } from '../../lib/localRecords';
export function AdminPayoutPage() {
  const id = useParams().payoutId ?? '';
  if (!/^[1-9]\d*$/.test(id) || !Number.isSafeInteger(Number(id)))
    return (
      <main className="content seller-page">
        <p className="notice">올바른 지급 ID를 확인하세요.</p>
        <Link to="/admin/payouts">지급 목록</Link>
      </main>
    );
  return <PayoutEditor key={id} id={id} />;
}
function PayoutEditor({ id }: { id: string }) {
  const location = useLocation();
  const client = useQueryClient();
  const path = `/api/admin/settlements/payouts/${id}`;
  const [note, setNote] = useState('');
  const [asking, setAsking] = useState(false);
  const detail = useQuery({
    queryKey: ['admin-payout', getToken(), id],
    queryFn: () => api<PayoutDetail>(path),
  });
  const run = useOperation<PayoutRetryOutcome>(async () => {
    await Promise.all([detail.refetch(), client.invalidateQueries({ queryKey: ['admin-payouts'] })]);
  });
  useEffect(() => {
    if (run.data) setAsking(false);
  }, [run.data]);
  const back =
    typeof location.state?.operationReturn === 'string' &&
    location.state.operationReturn.startsWith('/admin/payouts?')
      ? location.state.operationReturn
      : '/admin/payouts';
  if (detail.isPending)
    return (
      <main className="content seller-page">
        <Loading />
      </main>
    );
  if (!detail.data)
    return (
      <main className="content seller-page stack">
        <Link to={back}>‹ 지급 목록</Link>
        <ErrorNotice error={detail.error} />
        <button className="btn btn-ghost" onClick={() => detail.refetch()}>
          다시 조회
        </button>
      </main>
    );
  const { current: c, commands } = detail.data;
  const busy = run.isPending || detail.isFetching;
  const eligible = c.status === 'FAILED' && !c.inquiryOnly;
  const execute = () =>
    run.execute(`${path}/retry`, {
      commandId: newCommandId(),
      note: note.trim(),
      expectedVersion: c.version,
      expectedAttempt: c.attempt,
    });
  return (
    <main className="content seller-page admin-page stack">
      <Link to={back} className="navlink">
        ‹ 지급 목록
      </Link>
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">지급 상세</h1>
          <p>
            지급 {c.payoutId} · 정산서 {c.statementId} · 판매자 {c.sellerId}
          </p>
        </div>
        <button className="btn btn-ghost" disabled={busy} onClick={() => detail.refetch()}>
          새로고침
        </button>
      </header>
      {detail.isError && (
        <>
          <ErrorNotice error={detail.error} />
          <p className="notice">이전 조회 결과입니다. 최신 조회에 성공한 뒤 재시도하세요.</p>
        </>
      )}
      <section className="panel operation-summary">
        <div>
          <span className="pill">{payoutStatusLabel(c.status)}</span>
          <h2>{won(c.amount)}</h2>
          <p>판매자 {c.sellerId}</p>
        </div>
        <div>
          <span className="pill on">{payoutAttentionLabel(c.attention)}</span>
          <p>
            {c.doneAt
              ? '완료 ' + formatDateTime(c.doneAt)
              : c.requestedAt
                ? '요청 ' + formatDateTime(c.requestedAt)
                : '지급 요청 전'}
          </p>
        </div>
      </section>
      <div className="operation-detail-grid">
        <Panel title="지급 정보">
          <Facts
            items={[
              ['지급대행 상태', c.providerStatus],
              ['조회 전용', c.inquiryOnly ? '예' : '아니요'],
              ['확인 사유', c.verificationReason],
              ['실행 버전 / 시도', `${c.version} / ${c.attempt}`],
              ['계좌 정보', c.bankCode ? `${c.bankCode} / ${c.holderMasked ?? '정보 없음'}` : null],
              ['실패 코드', c.failureCode],
              ['마지막 오류', c.lastError],
              ['다음 시도', c.nextAttemptAt && formatDateTime(c.nextAttemptAt)],
            ]}
          />
        </Panel>
        <section className="panel stack operation-action-panel">
          <h2 className="panel-title">지급 재시도</h2>
          <p className="account-hint">
            지급대행이 실패를 확정한 건만 지급 대기로 되돌립니다. 결과 불명·조회 전용·완료 건은 다시 지급하지
            않습니다. 요청 접수는 송금 완료를 뜻하지 않습니다.
          </p>
          {run.isError && <ErrorNotice error={run.error} />}
          {run.data && (
            <Done>
              {run.data.result} · {run.data.explanation}
            </Done>
          )}
          {run.uncertain && (
            <p className="notice" role="status">
              요청 결과를 확인하지 못했어요. 같은 명령과 입력으로 결과를 확인하세요. 이 화면을 유지해 주세요.
            </p>
          )}
          <form
            className="stack"
            onSubmit={(e) => {
              e.preventDefault();
              if (!busy && eligible && note.trim() && !run.uncertain) {
                run.reset();
                setAsking(true);
              }
            }}
          >
            <Field id="payout-note" label="재시도 메모 (1~200자)">
              <textarea
                id="payout-note"
                className="input"
                required
                maxLength={200}
                rows={4}
                value={note}
                disabled={busy || run.uncertain || !eligible}
                onChange={(e) => setNote(e.target.value)}
              />
            </Field>
            {run.uncertain ? (
              <button type="button" className="btn btn-primary" disabled={busy} onClick={execute}>
                같은 명령 결과 확인
              </button>
            ) : (
              <button
                className="btn btn-primary"
                disabled={busy || detail.isError || !eligible || !note.trim()}
              >
                재시도 요청
              </button>
            )}
          </form>
          {!eligible && <p className="account-hint">실패가 확정된 지급만 재시도할 수 있어요.</p>}
        </section>
      </div>
      <OperationHistory commands={commands} />
      {asking && (
        <OperationConfirm
          title="지급 재시도를 요청할까요?"
          busy={run.isPending}
          error={run.error}
          onCancel={() => setAsking(false)}
          onConfirm={execute}
        >
          <p>
            지급 {c.payoutId} · {won(c.amount)}
          </p>
          <p>메모: {note}</p>
          <p>실패가 확정된 지급을 대기 상태로 되돌립니다. 실제 지급은 이후 실행됩니다.</p>
        </OperationConfirm>
      )}
    </main>
  );
}
