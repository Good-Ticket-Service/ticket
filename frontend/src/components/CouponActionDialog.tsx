import { useEffect, useRef } from 'react';
export function CouponActionDialog({
  kind,
  name,
  busy,
  error,
  onConfirm,
  onDismiss,
}: {
  kind: 'close' | 'delete';
  name: string;
  busy: boolean;
  error: string | null;
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
      aria-labelledby="coupon-action-title"
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onDismiss();
      }}
    >
      <h2 id="coupon-action-title">{kind === 'close' ? '쿠폰 발급을 중단할까요?' : '쿠폰을 삭제할까요?'}</h2>
      <strong>{name}</strong>
      <p>
        {kind === 'close'
          ? '이 쿠폰의 신규 발급을 중단합니다. 이미 발급된 회원 쿠폰은 그대로 유지됩니다.'
          : '발급 이력이 없는 쿠폰만 삭제할 수 있어요. 삭제된 쿠폰은 조회와 발급 목록에서 제외됩니다.'}
      </p>
      {error && (
        <p className="notice" role="alert">
          {error}
        </p>
      )}
      <div className="booking-dialog-actions">
        <button autoFocus type="button" className="btn btn-ghost" disabled={busy} onClick={onDismiss}>
          돌아가기
        </button>
        <button
          type="button"
          className={kind === 'delete' ? 'btn btn-danger' : 'btn btn-primary'}
          disabled={busy}
          onClick={onConfirm}
        >
          {busy ? '처리 중' : kind === 'close' ? '발급 중단하기' : '쿠폰 삭제하기'}
        </button>
      </div>
    </dialog>
  );
}
