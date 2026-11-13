import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { apiWithEtag, getToken } from '../../api/client';
import type { SellerSchedule } from '../../api/manageTypes';
import { ErrorNotice, Loading } from '../../components/Common';
import { Facts, Panel } from '../../components/Form';
import { formatDateTime } from '../../lib/format';
import { operationLabel, salesStatusLabel, seatSetupLabel } from '../../lib/labels';

export function SellerSchedulePage() {
  const { scheduleId } = useParams();
  if (!scheduleId || !/^[1-9]\d*$/.test(scheduleId) || !Number.isSafeInteger(Number(scheduleId)))
    return (
      <main className="content seller-page">
        <p className="notice">올바른 회차 ID를 입력해 주세요.</p>
        <Link to="/seller">공연 관리</Link>
      </main>
    );
  return <Schedule key={scheduleId} id={scheduleId} />;
}
function Schedule({ id }: { id: string }) {
  const [copied, setCopied] = useState(false),
    [copyError, setCopyError] = useState(false);
  const schedule = useQuery({
    queryKey: ['seller-schedule', id, getToken()],
    queryFn: () => apiWithEtag<SellerSchedule>(`/api/seller/schedules/${id}`),
    refetchInterval: (q) => (q.state.data?.data.seatSetupStatus === 'PENDING' ? 3000 : false),
  });
  if (schedule.isPending)
    return (
      <main className="content seller-page">
        <Loading />
      </main>
    );
  if (!schedule.data)
    return (
      <main className="content seller-page">
        <ErrorNotice error={schedule.error} />
        <button className="btn btn-ghost" onClick={() => schedule.refetch()}>
          회차 다시 불러오기
        </button>
      </main>
    );
  const { data: item, etag } = schedule.data;
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(
        `회차 ID: ${item.id}\n공연 ID: ${item.performanceId}\nversion: ${item.version}\nETag: ${etag ?? '미제공'}`,
      );
      setCopied(true);
      setCopyError(false);
    } catch {
      setCopyError(true);
    }
  };
  return (
    <main className="content seller-page seller-schedule stack">
      <Link className="seller-back" to="/seller/schedules">
        ‹ 회차 관리
      </Link>
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">회차 관리</h1>
          <p>
            회차 {item.id} · 공연 {item.performanceId}
          </p>
        </div>
        <button
          className="btn btn-ghost"
          disabled={schedule.isFetching}
          type="button"
          onClick={() => schedule.refetch()}
        >
          {schedule.isFetching ? '확인 중…' : '상태 다시 확인'}
        </button>
      </header>
      <section className={`seller-setup-banner ${item.seatSetupStatus.toLowerCase()}`} aria-live="polite">
        <span className={`seller-state ${item.seatSetupStatus.toLowerCase()}`}>
          {seatSetupLabel(item.seatSetupStatus)}
        </span>
        <h2>
          {item.seatSetupStatus === 'READY'
            ? '좌석 준비가 완료됐어요'
            : item.seatSetupStatus === 'PENDING'
              ? '좌석을 준비하고 있어요'
              : '좌석 준비 상태를 확인해 주세요'}
        </h2>
        <p>
          {item.seatSetupStatus === 'READY'
            ? '실제 판매 가능 여부는 예매 기간·공개·판매 중지 상태에 따라 달라집니다.'
            : item.seatSetupStatus === 'PENDING'
              ? '서버에 저장된 준비 상태를 자동으로 확인합니다.'
              : '준비 실패 후에도 서버 확인 결과에 따라 상태가 바뀔 수 있어요. 상태를 다시 확인해 주세요.'}
        </p>
        {item.seatSetupError && <p className="seller-error-text">{item.seatSetupError}</p>}
        <small>마지막 확인 {formatDateTime(new Date(schedule.dataUpdatedAt).toISOString())}</small>
      </section>
      {schedule.isError && (
        <div>
          <ErrorNotice error={schedule.error} />
          <p className="account-hint">아래 내용은 마지막으로 받은 상태이며, 최신 상태와 다를 수 있어요.</p>
        </div>
      )}
      <div className="seller-schedule-grid">
        <Panel title="공연 일정">
          <Facts
            items={[
              ['공연 시작', formatDateTime(item.startAt)],
              ['예매 오픈', formatDateTime(item.bookingOpenAt)],
              ['예매 마감', formatDateTime(item.bookingCloseAt)],
              [
                '공연',
                <Link to={`/seller/performances/${item.performanceId}`}>
                  공연 {item.performanceId} 설정 ↗
                </Link>,
              ],
            ]}
          />
        </Panel>
        <Panel title="판매·운영 상태">
          <Facts
            items={[
              [
                '판매',
                item.salesStatus === 'UNAVAILABLE' && !item.salesSuspended
                  ? '판매 불가'
                  : salesStatusLabel(item.salesStatus),
              ],
              ['운영', operationLabel(item.operationStatus)],
              [
                '판매 중지',
                item.salesSuspended ? `중지됨 · ${item.suspensionReason ?? '사유 미제공'}` : '중지되지 않음',
              ],
              [
                '회차 취소',
                item.canceledAt
                  ? `${formatDateTime(item.canceledAt)} · ${item.cancelReason ?? ''}`
                  : '취소되지 않음',
              ],
            ]}
          />
        </Panel>
      </div>
      <Panel
        title="관리자 전달 정보"
        note="판매 중지 등 관리자 작업에 필요한 현재 회차 정보를 복사할 수 있어요."
      >
        <Facts
          items={[
            ['회차 ID', item.id],
            ['회차 version', item.version],
            ['ETag', etag ?? '미제공'],
            ['좌석 구성 version', item.seatConfigVersion],
          ]}
        />
        <div>
          <button className="btn btn-ghost" type="button" onClick={copy}>
            {copied ? '전달 정보 복사됨' : '전달 정보 복사'}
          </button>
        </div>
        {copyError && (
          <p role="status" className="account-hint">
            복사하지 못했어요. 위 정보를 직접 복사해 주세요.
          </p>
        )}
        <p className="account-hint">
          상태 다시 확인은 조회만 수행합니다. 좌석 준비를 다시 요청하거나 판매 상태를 변경하지 않습니다.
        </p>
      </Panel>
    </main>
  );
}
