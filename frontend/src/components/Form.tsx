import type { ReactNode } from 'react';

/** 라벨·입력·도움말 한 칸. id는 라벨과 입력을 잇는 데 쓴다. */
export function Field({ id, label, hint, children }: { id: string; label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

/** 관리 화면의 한 덩어리. 제목과 설명을 위에 두고 내용을 아래에 둔다. */
export function Panel({ title, note, badge, children }: { title: string; note?: ReactNode; badge?: ReactNode; children: ReactNode }) {
  return (
    <section className="panel stack">
      <div>
        <h2 className="panel-title">
          {title}
          {badge}
        </h2>
        {note && <p className="muted panel-note">{note}</p>}
      </div>
      {children}
    </section>
  );
}

/** 이름-값 목록. 값이 null이면 '-'로 보인다. */
export function Facts({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="rows">
      {items.map(([label, value]) => (
        <div key={label} className="row">
          <dt>{label}</dt>
          <dd className="num">{value ?? '-'}</dd>
        </div>
      ))}
    </dl>
  );
}

/** 단계가 끝났는지 보인다. */
export function StepBadge({ done, label }: { done: boolean; label?: string }) {
  return <span className={done ? 'pill on' : 'pill'}>{label ?? (done ? '완료' : '필요')}</span>;
}

/** 평소에는 접어 두고, 버전이 맞지 않을 때만 펼쳐 version을 직접 넣는다. */
export function VersionOverride({
  id,
  open,
  current,
  value,
  onChange,
}: {
  id: string;
  open: boolean;
  current: number | null;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <details className="advanced" open={open}>
      <summary>버전이 맞지 않을 때</summary>
      <Field
        id={id}
        label={`version 직접 입력 (지금 ${current ?? '모름'})`}
        hint="다른 곳에서 먼저 바꿨거나 이 브라우저에서 만들지 않았으면 저장이 거절돼요. 맞는 version을 넣고 다시 시도해요."
      >
        <input id={id} className="input" type="number" min={0} value={value} onChange={(e) => onChange(e.target.value)} />
      </Field>
    </details>
  );
}

/** 되돌릴 수 없는 동작 전에 묻는 대화상자. 예매 취소 대화상자와 같은 모양이다. */
export function ConfirmDialog({
  title,
  body,
  confirmLabel,
  busy,
  onConfirm,
  onCancel,
}: {
  title: string;
  body: ReactNode;
  confirmLabel: string;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="overlay">
      <div role="dialog" aria-modal="true" aria-labelledby="confirm-title" className="dialog">
        <h2 id="confirm-title">{title}</h2>
        <p>{body}</p>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={onConfirm}>
          {confirmLabel}
        </button>
        <button type="button" className="btn-link" onClick={onCancel}>
          돌아가기
        </button>
      </div>
    </div>
  );
}

export function Done({ children }: { children: ReactNode }) {
  return (
    <p className="done" role="status">
      {children}
    </p>
  );
}
