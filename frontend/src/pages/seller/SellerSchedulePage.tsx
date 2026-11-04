import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { apiWithEtag } from '../../api/client';
import type { SellerSchedule } from '../../api/manageTypes';
import { ErrorNotice, Loading, Title } from '../../components/Common';
import { Facts, Panel } from '../../components/Form';
import { formatDateTime } from '../../lib/format';
import { operationLabel, salesStatusLabel, seatSetupLabel } from '../../lib/labels';

export function SellerSchedulePage() {
  const { scheduleId } = useParams();
  const schedule = useQuery({
    queryKey: ['seller-schedule', scheduleId],
    queryFn: () => apiWithEtag<SellerSchedule>(`/api/seller/schedules/${scheduleId}`),
    // 좌석 준비는 서버가 비동기로 진행하므로 끝날 때까지 다시 읽는다.
    refetchInterval: (query) => (query.state.data?.data.seatSetupStatus === 'PENDING' ? 3000 : false),
  });
  if (schedule.isPending) return <main className="content narrow"><Loading /></main>;
  if (schedule.isError) return <main className="content narrow"><ErrorNotice error={schedule.error} /></main>;
  const { data: item, etag } = schedule.data;
  return (
    <main className="content narrow stack" style={{ gap: 24 }}>
      <Link to="/seller" className="navlink" style={{ paddingLeft: 0 }}>
        ← 판매 관리
      </Link>
      <Title en="Schedule" ko={`회차 #${item.id}`} />
      <Panel title={formatDateTime(item.startAt)} note="관리자가 판매를 중지하려면 이 회차의 version이 필요해요.">
        <Facts
          items={[
            ['공연 ID', <Link to={`/seller/performances/${item.performanceId}`}>{item.performanceId}</Link>],
            ['예매 기간', `${formatDateTime(item.bookingOpenAt)} ~ ${formatDateTime(item.bookingCloseAt)}`],
            ['판매 상태', salesStatusLabel(item.salesStatus)],
            ['운영 상태', operationLabel(item.operationStatus)],
            ['좌석 준비', item.seatSetupStatus === 'PENDING' ? '준비 중 (자동으로 다시 확인해요)' : seatSetupLabel(item.seatSetupStatus)],
            ['좌석 준비 오류', item.seatSetupError],
            ['좌석 구성 version', item.seatConfigVersion],
            ['판매 중지', item.salesSuspended ? `중지됨 · ${item.suspensionReason ?? ''}` : '아니요'],
            ['취소', item.canceledAt && `${formatDateTime(item.canceledAt)} · ${item.cancelReason ?? ''}`],
            ['version (ETag)', `${item.version} (${etag ?? '-'})`],
          ]}
        />
      </Panel>
    </main>
  );
}
