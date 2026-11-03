import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { StatementDetail } from '../../api/manageTypes';
import { ErrorNotice, Loading, Title } from '../../components/Common';
import { Facts, Panel } from '../../components/Form';
import { formatDateTime, won } from '../../lib/format';
import { STATEMENT_STATUS_LABEL } from './SellerSettlementsPage';

export function SellerStatementPage() {
  const { statementId } = useParams();
  const statement = useQuery({
    queryKey: ['seller-statement', statementId],
    queryFn: () => api<StatementDetail>(`/api/seller/settlements/${statementId}`),
  });
  if (statement.isPending) return <main className="content narrow"><Loading /></main>;
  if (statement.isError) return <main className="content narrow"><ErrorNotice error={statement.error} /></main>;
  const item = statement.data;
  return (
    <main className="content narrow stack" style={{ gap: 24 }}>
      <Link to="/seller/settlements" className="navlink" style={{ paddingLeft: 0 }}>
        ← 정산
      </Link>
      <Title en="Statement" ko={`정산서 #${item.statementId}`} />
      <Panel title={won(item.payableAmount)}>
        <Facts
          items={[
            ['상태', STATEMENT_STATUS_LABEL[item.status]],
            ['회차', `#${item.scheduleId} · ${formatDateTime(item.scheduleStartAt)}`],
            ['차수', `${item.sequence}차`],
            ['판매 합계', won(item.saleTotal)],
            ['상계 합계', won(item.offsetTotal)],
            ['발행', formatDateTime(item.issuedAt)],
            ['지급', item.paidAt && formatDateTime(item.paidAt)],
            ['지급 상태', item.payoutStatus],
          ]}
        />
      </Panel>
      <Panel title="내역" note="판매는 판매자 몫 전액, 차감은 이 정산서에서 실제로 뺀 몫이에요.">
        {item.lines.length === 0 && <p className="muted">내역이 없어요.</p>}
        {item.lines.map((line) => (
          <div key={line.itemId} className="item">
            <span>
              <span className="name">{line.kind === 'SALE' ? '판매' : '차감'}</span>
              <br />
              <span className="sub num">
                결제 #{line.paymentId}
                {line.kind === 'SALE' &&
                  ` · 결제 ${won(line.grossAmount)} − 수수료 ${won(line.commission)} − 부가세 ${won(line.commissionVat)}`}
              </span>
            </span>
            <span className="name num">{line.kind === 'SALE' ? won(line.amount) : `−${won(line.amount)}`}</span>
          </div>
        ))}
      </Panel>
    </main>
  );
}
