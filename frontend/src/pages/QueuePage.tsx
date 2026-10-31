import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import type { QueuePosition } from '../api/types';
import { ErrorNotice, Loading, Title } from '../components/Common';

export function QueuePage() {
  const { performanceId } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const client = useQueryClient();
  const scheduleId = params.get('scheduleId');
  const seatsPath = `/performances/${performanceId}/schedules/${scheduleId}/seats`;
  const path = `/api/queues/performances/${performanceId}`;

  const join = useMutation({
    mutationFn: () => api<QueuePosition>(path, { method: 'POST' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['queue', performanceId] }),
    onError: (error) => {
      // 대기열을 끈 환경에서는 줄을 서지 않고 바로 좌석으로 간다.
      if (error instanceof ApiError && error.code === 'QUEUE_DISABLED') navigate(seatsPath, { replace: true });
    },
  });
  const { mutate: enter } = join;
  useEffect(() => enter(), [enter]);

  const me = useQuery({
    queryKey: ['queue', performanceId],
    enabled: join.isSuccess,
    queryFn: () => api<QueuePosition>(`${path}/me`),
    refetchInterval: 3000,
    gcTime: 0,
  });

  const leave = async () => {
    await api(`${path}/me`, { method: 'DELETE' }).catch(() => undefined);
    navigate(`/performances/${performanceId}`);
  };

  const position = me.data ?? join.data;
  return (
    <main className="content narrow">
      <div className="queue">
        <Title en="Waiting" ko="입장 대기" />
        {join.isPending && <Loading />}
        {join.isError && !(join.error instanceof ApiError && join.error.code === 'QUEUE_DISABLED') && <ErrorNotice error={join.error} />}
        {position && (
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
