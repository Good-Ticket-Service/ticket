import { useEffect, useRef } from 'react';
import { ErrorNotice } from './Common';

export function CancelBookingDialog({
  title,
  pending,
  allowed,
  error,
  onConfirm,
  onDismiss,
}: {
  title: string;
  pending: boolean;
  allowed: boolean;
  error: unknown;
  onConfirm: () => void;
  onDismiss: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current!;
    dialog.showModal();
    return () => dialog.close();
  }, []);
  return (
    <dialog
      ref={ref}
      className="booking-cancel-dialog"
      aria-labelledby="booking-cancel-title"
      onCancel={(event) => {
        event.preventDefault();
        if (!pending) onDismiss();
      }}
    >
      <h2 id="booking-cancel-title">예매를 취소할까요?</h2>
      <strong>{title}</strong>
      <p>취소하면 같은 좌석을 다시 확보할 수 없어요. 쿠폰은 원래 유효기간에 따라 쿠폰함에 표시돼요.</p>
      <p>취소 접수 후 PG 환불 처리와 카드·계좌 반영에는 시간이 걸릴 수 있어요.</p>
      {!allowed && !pending && (
        <p className="notice">취소 가능한 시간이 지났어요. 최신 상태를 확인해 주세요.</p>
      )}
      {error != null && <ErrorNotice error={error} />}
      <div className="booking-dialog-actions">
        <button autoFocus type="button" className="btn btn-ghost" disabled={pending} onClick={onDismiss}>
          돌아가기
        </button>
        <button type="button" className="btn btn-danger" disabled={pending || !allowed} onClick={onConfirm}>
          {pending ? '취소 요청 중' : '예매 취소하기'}
        </button>
      </div>
    </dialog>
  );
}
