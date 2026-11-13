import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useEffect, useState } from 'react';
import { api, ApiError, getToken } from '../../api/client';
import type { WaitingRoom } from '../../api/types';
import { ErrorNotice, Loading } from '../../components/Common';
import { useLocalRecords } from '../../lib/localRecords';
import { roomScopes, roomTimeError } from '../../lib/adminManagement';
import { formatDateTime, parseTime, seoulInputToOffset, toSeoulInput } from '../../lib/format';
interface RoomRecord extends WaitingRoom {
  id: number;
}
export function AdminWaitingRoomsPage() {
  const client = useQueryClient();
  const records = useLocalRecords<RoomRecord>('ticket.admin.waitingRooms');
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const [ids, setIds] = useState(''),
    [openAt, setOpenAt] = useState(() => toSeoulInput(undefined, 10)),
    [closeAt, setCloseAt] = useState(() => toSeoulInput(undefined, 70)),
    [validation, setValidation] = useState<string | null>(null);
  const [lookup, setLookup] = useState(''),
    [scope, setScope] = useState('');
  const remember = (room: WaitingRoom) => records.save({ ...room, id: room.roomId });
  const query = useQuery({
    queryKey: ['admin-waiting-room', getToken(), scope],
    enabled: !!scope,
    retry: false,
    queryFn: async () => {
      try {
        return await api<WaitingRoom>(`/api/waiting-rooms?scope=${encodeURIComponent(scope)}`, {
          cache: 'no-store',
        });
      } catch (error) {
        if (error instanceof ApiError && error.code === 'WAITING_ROOM_NOT_FOUND') return null;
        throw error;
      }
    },
  });
  const create = useMutation({
    mutationFn: (body: { scopes: string[]; openAt: string; closeAt: string }) =>
      api<WaitingRoom>('/api/admin/waiting-rooms', { method: 'POST', body }),
    onSuccess: (room) => {
      room.scopes.forEach((value) =>
        client.setQueryData<WaitingRoom>(['admin-waiting-room', getToken(), value], room),
      );
      remember(room);
      setLookup(room.scopes[0].slice('schedule:'.length));
      setScope(room.scopes[0]);
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (create.isPending) return;
    create.reset();
    const scopes = roomScopes(ids);
    const error = !scopes
      ? '회차 ID를 1~100개 입력해 주세요. 0으로 시작하거나 중복 개수로 한도를 넘길 수 없어요.'
      : roomTimeError(openAt, closeAt);
    setValidation(error);
    if (!error && scopes)
      create.mutate({ scopes, openAt: seoulInputToOffset(openAt), closeAt: seoulInputToOffset(closeAt) });
  };
  const find = (id: string) => {
    const values = roomScopes(id);
    if (!values || values.length !== 1) return;
    setLookup(id);
    if (scope === values[0]) query.refetch();
    else setScope(values[0]);
  };
  const state = (room: WaitingRoom) =>
    now < parseTime(room.openAt).getTime()
      ? '오픈 전'
      : now < parseTime(room.closeAt).getTime()
        ? '진행 중'
        : '종료';
  return (
    <main className="content seller-page admin-page stack">
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">대기열 관리</h1>
          <p>회차를 보호할 대기열을 생성하고 scope로 확인하세요.</p>
        </div>
      </header>
      <div className="admin-room-layout">
        <section className="panel stack admin-accent-panel">
          <h2 className="panel-title">대기열 생성</h2>
          <p className="account-hint">
            하나의 대기열로 보호할 회차를 모두 입력하세요. 생성한 회차 묶음·시각은 수정할 수 없습니다.
          </p>
          <form className="stack" onSubmit={submit}>
            <fieldset className="seller-fieldset stack" disabled={create.isPending}>
              {validation && (
                <p className="notice" role="alert">
                  {validation}
                </p>
              )}
              {create.isError && (
                <>
                  <ErrorNotice error={create.error} />
                  <p className="account-hint">
                    생성 결과를 확인하지 못했다면 같은 회차를 조회해 생성 여부부터 확인해 주세요.
                  </p>
                </>
              )}
              {create.isSuccess && (
                <p className="account-success" role="status">
                  대기열 {create.data.roomId}을 생성했어요.
                </p>
              )}
              <div className="field">
                <label htmlFor="room-schedules">보호할 회차 ID</label>
                <textarea
                  id="room-schedules"
                  className="input"
                  required
                  placeholder="예: 100, 101, 102"
                  value={ids}
                  onChange={(event) => setIds(event.target.value)}
                />
                <p className="account-hint">
                  쉼표·줄바꿈·공백으로 구분, 최대 100개. scope는 schedule:회차ID로 생성합니다.
                </p>
              </div>
              <div className="field">
                <label htmlFor="room-open">대기열 오픈</label>
                <input
                  id="room-open"
                  className="input"
                  type="datetime-local"
                  required
                  value={openAt}
                  onInput={(event) => setOpenAt(event.currentTarget.value)}
                />
              </div>
              <div className="field">
                <label htmlFor="room-close">대기열 종료</label>
                <input
                  id="room-close"
                  className="input"
                  type="datetime-local"
                  required
                  value={closeAt}
                  onInput={(event) => setCloseAt(event.currentTarget.value)}
                />
              </div>
              <p className="account-hint">
                서울 시각 기준입니다. 오픈은 현재보다 최소 1분 뒤, 종료는 오픈 이후여야 합니다.
              </p>
              <button className="btn btn-primary">{create.isPending ? '생성 중' : '대기열 생성'}</button>
            </fieldset>
          </form>
        </section>
        <section className="panel stack">
          <h2 className="panel-title">회차의 대기열 조회</h2>
          <form
            className="stack"
            onSubmit={(event) => {
              event.preventDefault();
              find(lookup);
            }}
          >
            <div className="field">
              <label htmlFor="room-lookup">조회할 회차 ID</label>
              <input
                id="room-lookup"
                className="input"
                type="number"
                min={1}
                max={Number.MAX_SAFE_INTEGER}
                required
                value={lookup}
                onChange={(event) => setLookup(event.target.value)}
              />
            </div>
            <button className="btn btn-ghost" disabled={query.isFetching}>
              {query.isFetching ? '조회 중' : '대기열 조회'}
            </button>
          </form>
          {query.isPending && scope && <Loading />}
          {query.isError && <ErrorNotice error={query.error} />}{' '}
          {query.data === null && (
            <p role="status" className="account-hint">
              현재 이 회차를 보호하는 대기열이 없어요. 종료된 대기열은 조회되지 않습니다.
            </p>
          )}
          {query.data && (
            <div className="admin-room-result">
              <span className="seller-state ready">{state(query.data)}</span>
              <h3>대기열 {query.data.roomId}</h3>
              <dl className="settlement-account-facts">
                <div>
                  <dt>오픈</dt>
                  <dd>{formatDateTime(query.data.openAt)}</dd>
                </div>
                <div>
                  <dt>종료</dt>
                  <dd>{formatDateTime(query.data.closeAt)}</dd>
                </div>
                <div>
                  <dt>보호 범위</dt>
                  <dd>{query.data.scopes.join(' / ')}</dd>
                </div>
              </dl>
              <button className="chip" type="button" onClick={() => remember(query.data!)}>
                조회 결과를 브라우저에 기록
              </button>
              {query.isError && <p className="account-hint">위 정보는 마지막 조회 결과입니다.</p>}
            </div>
          )}
        </section>
      </div>
      <section className="panel stack">
        <h2 className="panel-title">브라우저에 기록된 대기열</h2>
        <p className="account-hint">
          서버 전체 목록이 아닙니다. 상태는 기록된 시각으로 표시하며, 종료 전 실제 연결은 회차 조회로 확인해
          주세요.
        </p>
        <div className="seller-table-scroll">
          <table className="seller-table">
            <thead>
              <tr>
                <th>대기열 · 보호 범위</th>
                <th>오픈 / 종료</th>
                <th>시각 기준 상태</th>
                <th>확인</th>
              </tr>
            </thead>
            <tbody>
              {records.records.map((room) => (
                <tr key={room.roomId}>
                  <td>
                    대기열 {room.roomId}
                    <span className="seller-cell-meta">{room.scopes.join(' / ')}</span>
                  </td>
                  <td>
                    {formatDateTime(room.openAt)}
                    <span className="seller-cell-meta">{formatDateTime(room.closeAt)}</span>
                  </td>
                  <td>
                    <span className="seller-state">{state(room)}</span>
                  </td>
                  <td>
                    <button
                      className="chip"
                      type="button"
                      onClick={() => find(room.scopes[0].slice('schedule:'.length))}
                    >
                      scope 조회
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!records.records.length && <p className="seller-empty">기록된 대기열이 없어요.</p>}
      </section>
      <p className="account-hint">조기 종료·삭제·입장 인원 설정 API는 제공되지 않습니다.</p>
    </main>
  );
}
