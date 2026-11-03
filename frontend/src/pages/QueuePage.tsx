import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { QueueStatus } from '../api/types';
import { ErrorNotice, Loading, Title } from '../components/Common';
import { saveEntryToken } from '../lib/entryTokens';

function isCode(error: unknown, code: string): boolean {
  return error instanceof ApiError && error.code === code;
}

export function QueuePage() {
  const { performanceId } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const client = useQueryClient();
  const scheduleId = params.get('scheduleId');
  const seatsPath = `/performances/${performanceId}/schedules/${scheduleId}/seats`;
  const path = `/api/queues/performances/${performanceId}`;
  const expired = (location.state as { expired?: boolean } | null)?.expired === true;

  const join = useMutation({
    mutationFn: () => api<QueueStatus>(path, { method: 'POST' }),
    onSuccess: (result) => client.setQueryData(['queue', performanceId], result),
    onError: (error) => {
      // 대기열을 끈 환경에서는 Gateway도 입장 토큰을 검사하지 않으므로 바로 좌석으로 간다.
      if (isCode(error, 'QUEUE_DISABLED')) navigate(seatsPath, { replace: true });
    },
  });
  const { mutate: enter } = join;
  useEffect(() => enter(), [enter]);

  const me = useQuery({
    queryKey: ['queue', performanceId],
    enabled: join.isSuccess,
    queryFn: () => api<QueueStatus>(`${path}/me`),
    // 입장했거나 줄에서 빠졌으면 더 묻지 않는다. 다시 줄을 서면 진입 응답으로 갱신된다.
    refetchInterval: (query) =>
      query.state.status === 'error' || query.state.data?.status === 'ENTERED' ? false : 3000,
    gcTime: 0,
  });

  const position = me.data ?? join.data;
  useEffect(() => {
    if (position?.status !== 'ENTERED' || !position.entryToken || !position.entryExpiresAt) return;
    saveEntryToken(performanceId!, position.entryToken, position.entryExpiresAt);
    navigate(seatsPath, { replace: true });
  }, [position, performanceId, seatsPath, navigate]);

  const leave = async () => {
    await api(`${path}/me`, { method: 'DELETE' }).catch(() => undefined);
    navigate(`/performances/${performanceId}`);
  };

  const waiting = position?.status === 'WAITING' && !me.isError;
  return (
    <main className="content narrow">
      <div className="queue">
        <Title en="Waiting" ko="입장 대기" />
        {expired && <p className="notice">입장 시간이 끝났거나 입장 확인이 되지 않아 다시 줄을 섰어요.</p>}
        {join.isPending && <Loading />}
        {join.isError && !isCode(join.error, 'QUEUE_DISABLED') && <ErrorNotice error={join.error} />}
        {me.isError && (
          <>
            <ErrorNotice error={me.error} />
            {isCode(me.error, 'NOT_IN_QUEUE') && (
              <button type="button" className="btn btn-primary" disabled={join.isPending} onClick={() => enter()}>
                다시 줄 서기
              </button>
            )}
          </>
        )}
        {waiting && (
          <>
            <div>
              <div className="muted">내 대기 순번</div>
              <div className="position num">{position.position.toLocaleString('ko-KR')}</div>
            </div>
            <div className="grades">
              <div className="grade">
                <div className="name">내 앞 인원</div>
                <div className="value num">{position.aheadCount.toLocaleString('ko-KR')}명</div>
              </div>
              <div className="grade">
                <div className="name">예상 대기</div>
                <div className="value num">약 {Math.ceil(position.estimatedWaitSeconds / 60)}분</div>
              </div>
            </div>
            <p className="muted">이 창을 닫으면 줄에서 빠질 수 있어요. 차례가 되면 좌석 선택으로 넘어가요.</p>
          </>
        )}
        <button type="button" className="btn btn-ghost" onClick={leave}>
          대기 취소
        </button>
      </div>
    </main>
  );
}
