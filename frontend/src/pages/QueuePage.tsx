import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, ApiError, getToken } from '../api/client';
import type { QueueStatus, Schedule, WaitingRoom } from '../api/types';
import { ErrorNotice, Loading } from '../components/Common';
import { clearEntryToken, saveEntryToken } from '../lib/entryTokens';
import { clock, formatDateTime, parseTime } from '../lib/format';

const codeIs = (error: unknown, code: string) => error instanceof ApiError && error.code === code;
const positiveId = (value: string | null | undefined) => !!value && /^[1-9]\d*$/.test(value);

export function QueuePage() {
  const { performanceId } = useParams();
  const [params] = useSearchParams();
  const scheduleId = params.get('scheduleId');
  const navigate = useNavigate();
  const location = useLocation();
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const valid = positiveId(performanceId) && positiveId(scheduleId);
  const schedule = useQuery({
    queryKey: ['schedule', scheduleId],
    enabled: valid,
    queryFn: () => api<Schedule>(`/api/schedules/${scheduleId}`),
  });
  const matches = schedule.data?.performanceId === Number(performanceId);
  const room = useQuery({
    queryKey: ['waiting-room', scheduleId, getToken()],
    enabled: valid && matches,
    queryFn: ({ signal }) =>
      api<WaitingRoom>(`/api/waiting-rooms?scope=${encodeURIComponent(`schedule:${scheduleId}`)}`, {
        cache: 'no-store',
        signal,
      }),
    retry: false,
    gcTime: 0,
  });
  const expired = (location.state as { expired?: boolean } | null)?.expired === true;
  const seatsPath = `/performances/${performanceId}/schedules/${scheduleId}/seats`;
  const absent = codeIs(room.error, 'WAITING_ROOM_NOT_FOUND');
  const closed = !!room.data && parseTime(room.data.closeAt).getTime() <= now;
  useEffect(() => {
    if ((!absent && !closed) || expired) return;
    clearEntryToken(scheduleId!);
    navigate(seatsPath, { replace: true });
  }, [absent, closed, expired, scheduleId, seatsPath, navigate]);
  if (!valid)
    return (
      <main className="content narrow">
        <p className="notice">올바른 회차를 선택해 주세요.</p>
        <Link to="/">공연 목록</Link>
      </main>
    );
  if (schedule.isPending || (matches && room.isPending))
    return (
      <main className="content narrow">
        <Loading />
      </main>
    );
  if (schedule.isError)
    return (
      <main className="content narrow">
        <ErrorNotice error={schedule.error} />
        <Link to={`/performances/${performanceId}`}>공연 상세</Link>
      </main>
    );
  if (!matches)
    return (
      <main className="content narrow">
        <p className="notice">이 공연에 속한 회차가 아니에요.</p>
        <Link to={`/performances/${performanceId}`}>공연 상세</Link>
      </main>
    );
  return (
    <main className="content queue-content">
      <section className="queue-card" aria-labelledby="queue-heading">
        <span className="queue-eyebrow">SAFE TICKET · 입장 대기</span>
        <h1 id="queue-heading">
          {room.data && now < parseTime(room.data.openAt).getTime()
            ? '곧 예매 대기가 시작돼요'
            : '입장 순서를 확인하고 있어요'}
        </h1>
        <p className="queue-session">관람 {formatDateTime(schedule.data.startAt)}</p>
        {expired && (
          <p className="notice">입장권이 만료됐거나 확인되지 않았어요. 현재 대기열을 다시 확인해 주세요.</p>
        )}
        {absent || closed ? (
          <div className="stack">
            <p>현재 이 회차를 보호하는 대기열이 없어요.</p>
            {expired ? (
              <>
                <p className="booking-hint">
                  입장 정보가 갱신되는 중일 수 있어요. 자동 이동을 멈췄으니 잠시 뒤 다시 확인해 주세요.
                </p>
                <button type="button" className="btn btn-primary" onClick={() => room.refetch()}>
                  대기열 다시 확인
                </button>
                <Link className="btn btn-ghost" to={seatsPath}>
                  좌석 다시 확인
                </Link>
              </>
            ) : (
              <Loading />
            )}
          </div>
        ) : room.isError ? (
          <div className="stack">
            <ErrorNotice error={room.error} />
            <button type="button" className="btn btn-primary" onClick={() => room.refetch()}>
              대기열 다시 확인
            </button>
          </div>
        ) : room.data && !room.data.scopes.includes(`schedule:${scheduleId}`) ? (
          <p className="notice">회차의 대기열 정보를 확인하지 못했어요. 다시 시도해 주세요.</p>
        ) : room.data && now < parseTime(room.data.openAt).getTime() ? (
          <>
            <div className="queue-countdown" role="timer" aria-label="대기열 오픈까지 남은 시간">
              {clock(Math.ceil((parseTime(room.data.openAt).getTime() - now) / 1000))}
            </div>
            <p className="queue-note">
              {formatDateTime(room.data.openAt)} 오픈
              <br />
              오픈 시간이 되면 자동으로 입장을 요청해요.
            </p>
          </>
        ) : room.data ? (
          <RoomEntry
            key={`${room.data.roomId}:${scheduleId}`}
            room={room.data}
            scheduleId={scheduleId!}
            seatsPath={seatsPath}
          />
        ) : null}
        <Link className="queue-back" to={`/performances/${performanceId}`}>
          공연 상세로 돌아가기
        </Link>
      </section>
    </main>
  );
}

function RoomEntry({
  room,
  scheduleId,
  seatsPath,
}: {
  room: WaitingRoom;
  scheduleId: string;
  seatsPath: string;
}) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const [leaving, setLeaving] = useState(false);
  const leavingRef = useRef(false);
  const path = `/api/waiting-rooms/${room.roomId}/entries`;
  const queryKey = ['room-entry', room.roomId, getToken()];
  const join = useMutation({
    mutationFn: () => api<QueueStatus>(path, { method: 'POST' }),
    onSuccess: (data) => client.setQueryData(queryKey, data),
  });
  useEffect(() => {
    // 서버 진입은 같은 회원의 재요청에 기존 순번을 반환한다. 개발 StrictMode의 effect 재실행도 허용한다.
    join.mutate();
  }, []);
  const status = useQuery({
    queryKey,
    enabled: join.isSuccess && join.data?.status === 'WAITING' && !leaving,
    queryFn: ({ signal }) => api<QueueStatus>(`${path}/me`, { cache: 'no-store', signal }),
    refetchInterval: (query) =>
      query.state.status === 'error' || query.state.data?.status === 'ENTERED' ? false : 3000,
    refetchIntervalInBackground: true,
    gcTime: 0,
    retry: false,
  });
  const position = status.data ?? join.data;
  const failure = join.error ?? status.error;
  useEffect(() => {
    if (leavingRef.current) return;
    if (codeIs(failure, 'WAITING_ROOM_CLOSED') || codeIs(failure, 'WAITING_ROOM_NOT_FOUND')) {
      clearEntryToken(scheduleId);
      navigate(seatsPath, { replace: true });
      return;
    }
    if (
      join.isSuccess &&
      position?.status === 'ENTERED' &&
      position.entryToken &&
      position.entryExpiresAt &&
      !failure
    ) {
      saveEntryToken(room, position.entryToken, position.entryExpiresAt);
      navigate(seatsPath, { replace: true });
    }
  }, [position, failure, room, scheduleId, seatsPath, navigate, join.isSuccess]);
  const [leaveError, setLeaveError] = useState<unknown>(null);
  const leave = async () => {
    leavingRef.current = true;
    setLeaving(true);
    setLeaveError(null);
    await client.cancelQueries({ queryKey });
    try {
      await api(`${path}/me`, { method: 'DELETE' });
      client.removeQueries({ queryKey });
      navigate(seatsPath.split('/schedules/')[0], { replace: true });
    } catch (error) {
      setLeaveError(error);
      leavingRef.current = false;
      setLeaving(false);
    }
  };
  return (
    <div className="stack queue-entry">
      {join.isPending && <Loading />}
      {failure && (
        <>
          <ErrorNotice error={failure} />
          <button
            type="button"
            className="btn btn-primary"
            disabled={join.isPending}
            onClick={() => {
              client.removeQueries({ queryKey });
              join.mutate();
            }}
          >
            입장 다시 시도
          </button>
        </>
      )}
      {position?.status === 'WAITING' && !failure && (
        <>
          <div className="queue-rank">
            <span>내 대기 순번</span>
            <strong className="num">
              {position.position.toLocaleString('ko-KR')}
              <small>번</small>
            </strong>
          </div>
          <dl className="queue-metrics">
            <div>
              <dt>내 앞 대기</dt>
              <dd>{position.aheadCount.toLocaleString('ko-KR')}명</dd>
            </div>
            <div>
              <dt>예상 대기 시간</dt>
              <dd>약 {Math.max(1, Math.ceil(position.estimatedWaitSeconds / 60))}분</dd>
            </div>
          </dl>
          <p className="queue-note">
            순번은 자동으로 갱신돼요.
            <br />이 창을 유지하면 차례가 됐을 때 좌석 선택으로 이동해요.
          </p>
        </>
      )}
      {position?.status === 'ENTERED' && !failure && (
        <p role="status">입장이 확인됐어요. 좌석 선택으로 이동해요.</p>
      )}
      {leaveError != null && <ErrorNotice error={leaveError} />}
      <button type="button" className="btn btn-ghost" disabled={join.isPending || leaving} onClick={leave}>
        {leaving ? '대기를 취소하고 있어요' : '대기 취소'}
      </button>
    </div>
  );
}
