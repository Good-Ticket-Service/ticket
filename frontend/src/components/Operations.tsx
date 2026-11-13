import { useMutation } from '@tanstack/react-query';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { api, ApiError } from '../api/client';
import { commandSession } from '../lib/operationCommand';
import { formatDateTime } from '../lib/format';
import { ErrorNotice } from './Common';

export function useOperation<T>(refresh: () => Promise<unknown>) {
  const [session] = useState(commandSession);
  const [uncertain, setUncertain] = useState(false);
  const lock = useRef(false);
  const mutation = useMutation({
    mutationFn: (command: { path: string; body: Record<string, unknown> }) =>
      api<T>(command.path, { method: 'POST', body: command.body }),
    onSuccess: () => {
      session.settle();
      setUncertain(false);
    },
    onError: (error) => {
      const status = error instanceof ApiError ? error.status : 0;
      session.settle(status);
      setUncertain(status === 0 || status >= 500);
    },
    onSettled: async () => {
      try {
        await refresh();
      } finally {
        lock.current = false;
      }
    },
  });
  return {
    ...mutation,
    uncertain,
    execute(path: string, body: Record<string, unknown>) {
      if (lock.current) return;
      lock.current = true;
      mutation.mutate(session.prepare(path, body));
    },
  };
}

export function OperationConfirm({
  title,
  children,
  busy,
  error,
  onConfirm,
  onCancel,
}: {
  title: string;
  children: ReactNode;
  busy: boolean;
  error: unknown;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = dialog.current!;
    d.showModal();
    return () => d.close();
  }, []);
  return (
    <dialog
      ref={dialog}
      className="booking-cancel-dialog"
      aria-labelledby="operation-confirm-title"
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) onCancel();
      }}
    >
      <h2 id="operation-confirm-title">{title}</h2>
      <div className="operation-confirm-body">{children}</div>
      {error ? <ErrorNotice error={error} /> : null}
      <div className="actions">
        <button autoFocus className="btn btn-ghost" disabled={busy} onClick={onCancel}>
          돌아가기
        </button>
        <button className="btn btn-primary" disabled={busy} onClick={onConfirm}>
          {busy ? '처리 중' : '확인하고 요청'}
        </button>
      </div>
    </dialog>
  );
}

export function OperationHistory({
  commands,
}: {
  commands: {
    commandId: string;
    action: string;
    result: string;
    processedAt: string;
    actor: string;
    explanation: string;
    reason?: string;
    note?: string;
  }[];
}) {
  return (
    <section className="panel stack">
      <div className="operation-panel-heading">
        <h2 className="panel-title">명령 기록</h2>
        <span>최근 20개</span>
      </div>
      {!commands.length && <p className="seller-empty">등록된 명령 기록이 없어요.</p>}
      <ol className="operation-history">
        {commands.map((c) => (
          <li key={c.commandId}>
            <div>
              <strong>{c.action}</strong>
              <span className="pill">{c.result}</span>
              <time>{formatDateTime(c.processedAt)}</time>
            </div>
            <p>{c.explanation}</p>
            <small>
              {c.actor} · {c.note || c.reason}
            </small>
            <details>
              <summary>명령 식별자</summary>
              <code>{c.commandId}</code>
            </details>
          </li>
        ))}
      </ol>
    </section>
  );
}
