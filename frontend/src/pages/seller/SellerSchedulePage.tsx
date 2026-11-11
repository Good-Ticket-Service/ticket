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
  // 다시 확인이 실패해도 이미 받은 회차는 계속 보여 준다. 처음 조회부터 실패했을 때만 오류 화면이다.
  if (!schedule.data) return <main className="content narrow"><ErrorNotice error={schedule.error} /></main>;
  const { data: item, etag } = schedule.data;
  return (
    <main className="content narrow stack" style={{ gap: 24 }}>
      <Link to="/seller" className="navlink" style={{ paddingLeft: 0 }}>
        ← 공연 관리
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
        {item.seatSetupStatus === 'FAILED' && (
          // 결과를 받지 못한 실패는 서버가 나중에 결과를 확인해 READY로 바꿀 수 있다(이슈 #252).
          // 실패마다 복구된다고 단정하지 않고, 판매자가 직접 최신 상태를 다시 읽게 한다. 좌석 준비를 다시 요청하지 않는다.
          <div className="stack" style={{ gap: 8 }}>
            <p className="notice">좌석 준비에 실패했습니다. 이후 상태가 바뀔 수 있습니다.</p>
            <div>
              <button type="button" className="btn btn-ghost" disabled={schedule.isFetching} onClick={() => schedule.refetch()}>
                {schedule.isFetching ? '확인하는 중…' : '상태 다시 확인'}
              </button>
            </div>
            <p className="hint">
              좌석 준비를 다시 요청하지 않고, 서버에 저장된 지금 상태만 다시 읽어요. 마지막 성공 확인{' '}
              {new Date(schedule.dataUpdatedAt).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul' })}
            </p>
          </div>
        )}
        {schedule.isError && (
          // 다시 확인·PENDING 자동 조회·재진입 조회가 실패해도 받아 둔 회차는 그대로 두고, 조회 실패만 알린다.
          <div className="stack" style={{ gap: 4 }}>
            <ErrorNotice error={schedule.error} />
            <p className="hint">위 내용은 마지막으로 받은 상태예요. 최신 상태와 다를 수 있어요.</p>
          </div>
        )}
      </Panel>
    </main>
  );
}
