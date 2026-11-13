import { useQuery } from '@tanstack/react-query';
import { Link, useLocation, useParams } from 'react-router-dom';
import { api, getToken } from '../../api/client';
import type { StatementDetail } from '../../api/manageTypes';
import { ErrorNotice, Loading } from '../../components/Common';
import { formatDateTime, won } from '../../lib/format';
import { payoutStatusLabel } from '../../lib/labels';
import { STATEMENT_STATUS_LABEL } from '../../lib/settlements';

export function SellerStatementPage() {
  const { statementId } = useParams();
  if (!statementId || !/^[1-9]\d*$/.test(statementId) || !Number.isSafeInteger(Number(statementId)))
    return (
      <main className="content seller-page">
        <p className="notice">올바른 정산서 ID를 입력해 주세요.</p>
        <Link to="/seller/settlements">정산 내역</Link>
      </main>
    );
  return <Statement key={statementId} id={statementId} />;
}
function Statement({ id }: { id: string }) {
  const location = useLocation();
  const previous = location.state?.settlementReturn;
  const back =
    typeof previous === 'string' && previous.startsWith('/seller/settlements?')
      ? previous
      : '/seller/settlements';
  const query = useQuery({
    queryKey: ['seller-statement', getToken(), id],
    queryFn: () => api<StatementDetail>(`/api/seller/settlements/${id}`),
  });
  if (query.isPending)
    return (
      <main className="content seller-page">
        <Loading />
      </main>
    );
  if (!query.data)
    return (
      <main className="content seller-page">
        <ErrorNotice error={query.error} />
        <button className="btn btn-ghost" onClick={() => query.refetch()}>
          정산서 다시 불러오기
        </button>
      </main>
    );
  const item = query.data;
  const money = (value: number | null) => (value === null ? '—' : won(value));
  return (
    <main className="content seller-page stack">
      <Link className="seller-back" to={back}>
        ‹ 정산 내역
      </Link>
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">정산서 상세</h1>
          <p>
            정산서 {item.statementId} · {item.sequence}차
          </p>
        </div>
        <button
          type="button"
          className="btn btn-ghost"
          disabled={query.isFetching}
          onClick={() => query.refetch()}
        >
          최신 상태 확인
        </button>
      </header>
      {query.isError && (
        <>
          <ErrorNotice error={query.error} />
          <p className="account-hint">마지막으로 조회한 내역입니다. 최신 상태를 다시 확인해 주세요.</p>
        </>
      )}
      <section className="settlement-statement-total">
        <div>
          <span className={`seller-state ${item.status === 'PAID' ? 'ready' : 'draft'}`}>
            {STATEMENT_STATUS_LABEL[item.status]}
          </span>
          <h2>정산서 지급액</h2>
          <strong>{won(item.payableAmount)}</strong>
        </div>
        <dl>
          <div>
            <dt>판매자 몫</dt>
            <dd>{won(item.saleTotal)}</dd>
          </div>
          <div>
            <dt>상계 차감</dt>
            <dd>{won(item.offsetTotal)}</dd>
          </div>
        </dl>
      </section>
      <section className="panel stack">
        <h2 className="panel-title">발행·지급 정보</h2>
        <dl className="settlement-account-facts">
          <div>
            <dt>회차</dt>
            <dd>
              {item.scheduleId} · {formatDateTime(item.scheduleStartAt)}
            </dd>
          </div>
          <div>
            <dt>발행일시</dt>
            <dd>{formatDateTime(item.issuedAt)}</dd>
          </div>
          <div>
            <dt>지급일시</dt>
            <dd>{item.paidAt ? formatDateTime(item.paidAt) : '—'}</dd>
          </div>
          <div>
            <dt>지급 처리 상태</dt>
            <dd>
              {item.payoutStatus
                ? payoutStatusLabel(item.payoutStatus)
                : item.status === 'NO_PAYOUT'
                  ? '지급 없음'
                  : '정보 없음'}
            </dd>
          </div>
        </dl>
      </section>
      <section className="panel stack">
        <h2 className="panel-title">판매·차감 내역</h2>
        <p className="account-hint">판매는 판매자 몫 전액, 차감은 이 정산서에서 실제 상계한 금액입니다.</p>
        <div className="seller-table-scroll">
          <table className="seller-table settlement-table">
            <thead>
              <tr>
                <th>구분 · 결제 ID</th>
                <th>결제 금액</th>
                <th>수수료</th>
                <th>수수료 부가세</th>
                <th>정산 반영액</th>
              </tr>
            </thead>
            <tbody>
              {item.lines.map((line) => (
                <tr key={line.itemId}>
                  <td>
                    {line.kind === 'SALE' ? '판매' : '차감'}
                    <span className="seller-cell-meta">결제 {line.paymentId}</span>
                  </td>
                  <td>{money(line.grossAmount)}</td>
                  <td>{money(line.commission)}</td>
                  <td>{money(line.commissionVat)}</td>
                  <td>
                    <strong>
                      {line.kind === 'DEDUCTION' ? '−' : ''}
                      {won(line.amount)}
                    </strong>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {item.lines.length === 0 && <p className="seller-empty">표시할 내역이 없어요.</p>}
      </section>
    </main>
  );
}
