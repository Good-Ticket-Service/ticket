import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Reservation, ReservationStatus } from '../api/types';
import { artClass, ErrorNotice, Loading, STATUS_LABEL } from '../components/Common';
import { formatDateTime, parseTime, won } from '../lib/format';
import { seatLabel } from '../lib/seatLabels';

const COPY: Record<ReservationStatus, { word: string; title: string; body: string }> = {
  HOLD: { word: 'Try again', title: '결제가 되지 않았어요', body: '이번 결제는 승인되지 않았어요. 좌석과 쿠폰은 그대로 잡혀 있으니 남은 시간 안에 다시 결제할 수 있어요.' },
  PAYMENT_IN_PROGRESS: { word: 'Checking', title: '결제 결과를 확인하고 있어요', body: '1~2분 걸릴 수 있어요. 이 창을 닫아도 결제는 그대로 처리돼요. 다시 결제하지 마세요.' },
  CONFIRMED: { word: 'Confirmed', title: '예매가 완료됐어요', body: '회차 시작 전까지 취소할 수 있어요.' },
  CANCELING: { word: 'Canceling', title: '취소를 처리하고 있어요', body: '취소 요청은 받았어요. 이 창을 닫아도 취소는 계속 진행돼요.' },
  CANCELED: { word: 'Canceled', title: '예매가 취소됐어요', body: '환불 요청이 접수됐어요. 카드나 계좌에 돌아오기까지는 결제 수단에 따라 시간이 걸릴 수 있어요.' },
  EXPIRED: { word: 'Time out', title: '좌석을 잡아 둔 시간이 끝났어요', body: '좌석이 풀렸고, 잡아 둔 쿠폰은 쿠폰함으로 돌아갔어요. 예매하려면 좌석을 다시 골라 주세요.' },
  RELEASED: { word: 'Released', title: '좌석을 놓았어요', body: '잡아 둔 쿠폰은 쿠폰함으로 돌아갔어요.' },
};

/** 결과가 아직 정해지지 않은 상태. 이 동안에만 다시 읽는다. */
const PENDING: ReservationStatus[] = ['PAYMENT_IN_PROGRESS', 'CANCELING'];

export function BookingDetailPage() {
  const { reservationId } = useParams();
  const client = useQueryClient();
  const [asking, setAsking] = useState(false);
  const path = `/api/reservations/${reservationId}`;
  const query = useQuery({
    queryKey: ['reservation', reservationId],
    queryFn: () => api<Reservation>(path),
    refetchInterval: (state) => (state.state.data && PENDING.includes(state.state.data.status) ? 3000 : false),
  });
  const cancel = useMutation({
    mutationFn: () => api<Reservation>(`${path}/cancel`, { method: 'POST', body: {} }),
    onSuccess: (result) => {
      client.setQueryData(['reservation', reservationId], result);
      client.invalidateQueries({ queryKey: ['reservations'] });
      client.invalidateQueries({ queryKey: ['coupons'] });
    },
    onSettled: () => setAsking(false),
  });
  if (query.isPending) return <main className="content"><Loading /></main>;
  if (query.isError) return <main className="content"><ErrorNotice error={query.error} /></main>;
  const item = query.data;
  const copy = COPY[item.status];
  const cancellable = item.status === 'CONFIRMED' && parseTime(item.scheduleStartAt).getTime() > Date.now();
  const seatsPath = `/performances/${item.performanceId}/schedules/${item.scheduleId}/seats`;
  return (
    <main className="content medium">
      <div className="result">
        <section className="copy" aria-live="polite">
          <div className="word">{copy.word}</div>
          <h1>{copy.title}</h1>
          <p>{copy.body}</p>
          {cancel.isError && <ErrorNotice error={cancel.error} />}
          <div className="actions" style={{ marginTop: 10 }}>
            {item.status === 'HOLD' && <Link className="btn btn-primary" to={`${path.replace('/api', '')}/checkout`}>다시 결제하기</Link>}
            {(item.status === 'EXPIRED' || item.status === 'RELEASED') && <Link className="btn btn-primary" to={seatsPath}>좌석 다시 고르기</Link>}
            {item.status === 'CANCELED' && <Link className="btn btn-primary" to="/">공연 목록으로</Link>}
            <Link className="btn btn-ghost" to="/reservations">내 예매 목록</Link>
            {cancellable && (
              <button type="button" className="btn btn-ghost" onClick={() => setAsking(true)}>
                예매 취소
              </button>
            )}
          </div>
        </section>
        <aside className="ticket">
          <div className={`cover ${artClass(item.performanceId)}`}>
            <div className="heading">
              <span className={item.status === 'CONFIRMED' ? 'pill on' : 'pill'}>{STATUS_LABEL[item.status]}</span>
              <strong>{item.performanceTitle}</strong>
              <span className="muted num" style={{ fontSize: 14 }}>{formatDateTime(item.scheduleStartAt)}</span>
            </div>
          </div>
          <dl className="rows num">
            <div className="row">
              <dt>좌석</dt>
              <dd>{seatLabel(item.reservationId) ?? `${item.tickets.length}장`}</dd>
            </div>
            {item.tickets.filter((t) => t.ticketCode).map((ticket) => (
              <div key={ticket.scheduleSeatId} className="row">
                <dt>티켓 코드</dt>
                <dd className="code">{ticket.ticketCode}</dd>
              </div>
            ))}
            <div className="row">
              <dt>티켓 가격</dt>
              <dd>{won(item.totalPrice)}</dd>
            </div>
            {item.discountAmount ? (
              <div className="row">
                <dt>쿠폰 할인</dt>
                <dd>-{won(item.discountAmount)}</dd>
              </div>
            ) : null}
            {item.paymentAmount !== null && (
              <div className="row">
                <dt>{item.status === 'CANCELED' ? '결제했던 금액' : '결제 금액'}</dt>
                <dd>{won(item.paymentAmount)}</dd>
              </div>
            )}
          </dl>
        </aside>
      </div>
      {asking && (
        <div className="overlay">
          <div role="dialog" aria-modal="true" aria-labelledby="cancel-title" className="dialog">
            <h2 id="cancel-title">예매를 취소할까요?</h2>
            <p>취소하면 되돌릴 수 없고, 좌석은 다른 분이 예매할 수 있게 돼요. 사용한 쿠폰은 유효 기간이 남았으면 돌아와요.</p>
            <button type="button" className="btn btn-primary" disabled={cancel.isPending} onClick={() => cancel.mutate()}>
              예매 취소하기
            </button>
            <button type="button" className="btn-link" onClick={() => setAsking(false)}>
              돌아가기
            </button>
          </div>
        </div>
      )}
    </main>
  );
}
