import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { apiWithEtag, getToken } from '../api/client';
import type { SellerSchedule } from '../api/manageTypes';
import { ErrorNotice } from './Common';
import { formatDateTime } from '../lib/format';
import { salesStatusLabel, seatSetupLabel } from '../lib/labels';

export function SellerScheduleList({ ids }: { ids: number[] }) {
  const [limit, setLimit] = useState(10);
  if (!ids.length)
    return <p className="seller-empty">회차를 등록하면 이곳에서 준비 상태를 확인할 수 있어요.</p>;
  return (
    <>
      <div className="seller-table-scroll">
        <table className="seller-table">
          <thead>
            <tr>
              <th>회차 · 공연 일시</th>
              <th>판매 상태</th>
              <th>좌석 준비</th>
              <th>관리</th>
            </tr>
          </thead>
          <tbody>
            {ids.slice(0, limit).map((id) => (
              <ScheduleRow key={id} id={id} />
            ))}
          </tbody>
        </table>
      </div>
      {limit < ids.length && (
        <button className="chip" type="button" onClick={() => setLimit(limit + 10)}>
          회차 더 보기 ({limit}/{ids.length})
        </button>
      )}
    </>
  );
}
function ScheduleRow({ id }: { id: number }) {
  const query = useQuery({
    queryKey: ['seller-schedule', String(id), getToken()],
    queryFn: () => apiWithEtag<SellerSchedule>(`/api/seller/schedules/${id}`),
    refetchInterval: (q) => (q.state.data?.data.seatSetupStatus === 'PENDING' ? 3000 : false),
  });
  const item = query.data?.data;
  return (
    <tr>
      <td>
        <Link to={`/seller/schedules/${id}`}>회차 {id}</Link>
        {item && <span className="seller-cell-meta">{formatDateTime(item.startAt)}</span>}
      </td>
      <td>
        {item
          ? item.salesStatus === 'UNAVAILABLE' && !item.salesSuspended
            ? '판매 불가'
            : salesStatusLabel(item.salesStatus)
          : '—'}
      </td>
      <td>
        {item ? (
          <>
            <span className={`seller-state ${item.seatSetupStatus.toLowerCase()}`}>
              {seatSetupLabel(item.seatSetupStatus)}
            </span>
            {item.seatSetupError && (
              <span className="seller-cell-meta seller-error-text">{item.seatSetupError}</span>
            )}
          </>
        ) : query.isPending ? (
          '불러오는 중'
        ) : (
          '조회 실패'
        )}
        {query.isError && (
          <>
            <ErrorNotice error={query.error} />
            <small>마지막 확인 상태일 수 있어요.</small>
          </>
        )}
      </td>
      <td>
        <div className="seller-row-actions">
          <button
            className="chip"
            type="button"
            disabled={query.isFetching}
            onClick={() => query.refetch()}
            aria-label={`회차 ${id} 상태 다시 확인`}
          >
            상태 확인
          </button>
          <Link className="chip" to={`/seller/schedules/${id}`}>
            상세 ›
          </Link>
        </div>
      </td>
    </tr>
  );
}
