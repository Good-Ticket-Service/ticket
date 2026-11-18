import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, getToken } from '../api/client';
import type { HoldView } from '../api/types';
import { clock, parseTime } from '../lib/format';
import { forgetHold, rememberedHold } from '../lib/holdMemory';
import { seatLabel } from '../lib/seatLabels';
import { ErrorNotice } from './Common';

/**
 * 이 탭에서 잡아 둔 REDIS 모드 선점이 아직 살아 있으면 좌석 화면 위에 알려 결제 화면으로 돌려보낸다(1인 1매라 이 선점이 끝나기 전에는
 * 다른 좌석을 잡을 수 없다). 저장된 holdId가 없거나 선점이 끝났으면(404) 아무것도 보이지 않는다.
 */
export function PendingHold({
  scheduleId,
  checkKey,
  onReleased,
}: {
  scheduleId: string;
  /** 바뀌면 다시 조회한다. 좌석 선점이 매수 제한으로 막혔을 때 올린다. */
  checkKey: number;
  onReleased: () => void;
}) {
  const holdId = rememberedHold(scheduleId);
  const path = `/api/seats/schedules/${scheduleId}/holds/${holdId}`;
  const view = useQuery({
    queryKey: ['pending-hold', scheduleId, holdId, getToken(), checkKey],
    enabled: holdId !== null,
    retry: false,
    queryFn: async () => {
      try {
        return await api<HoldView>(path);
      } catch (error) {
        // 끝난 선점(만료·해제·좌석 선택 완료 실패)이거나 다른 회원의 값이면 잊는다
        if (error instanceof ApiError && error.code === 'HOLD_NOT_FOUND') {
          forgetHold(scheduleId, holdId!);
          return null;
        }
        throw error;
      }
    },
  });
  const release = useMutation({
    mutationFn: () => api(path, { method: 'DELETE' }),
    onSuccess: () => {
      forgetHold(scheduleId, holdId!);
      view.refetch();
      onReleased();
    },
  });
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const hold = view.data;
  if (!holdId || !hold) return null;
  const secondsLeft = Math.floor((parseTime(hold.holdExpiresAt).getTime() - now) / 1000);
  if (hold.status === 'HELD' && secondsLeft <= 0) return null;
  const seat = seatLabel(hold.holdId) ?? `${hold.gradeName} 좌석`;
  const target =
    hold.status === 'CONVERTED' && hold.reservationId !== null
      ? `/reservations/${hold.reservationId}/checkout`
      : `/seats/schedules/${scheduleId}/holds/${hold.holdId}/checkout`;
  return (
    <section className="pending-hold" role="status" aria-live="polite">
      <div>
        <strong>진행 중인 좌석 선점이 있어요</strong>
        <p>
          {seat}
          {hold.status === 'HELD' && <> · 남은 시간 {clock(secondsLeft)}</>}
          {hold.status !== 'HELD' && ' · 좌석 선택을 마쳤어요'}
        </p>
        <p className="pending-hold-note">1인 1매라 이 선점을 결제하거나 놓아야 다른 좌석을 고를 수 있어요.</p>
      </div>
      <div className="pending-hold-actions">
        <Link className="btn btn-primary" to={target}>
          결제 화면으로 이동
        </Link>
        {hold.status === 'HELD' && (
          <button
            type="button"
            className="btn btn-ghost"
            disabled={release.isPending}
            onClick={() => release.mutate()}
          >
            {release.isPending ? '놓고 있어요' : '이 좌석 놓기'}
          </button>
        )}
      </div>
      {release.isError && <ErrorNotice error={release.error} />}
    </section>
  );
}
