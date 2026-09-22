import { useEffect, useRef, type ReactNode } from 'react';
import { useFocusTrap } from '../lib/useFocusTrap';
import { cn } from '../lib/cn';

/**
 * The app's confirmation prompt, replacing `window.confirm`. A browser-native dialog is the one
 * place the product drops out of its own theme and typography entirely, and it renders the message
 * as one unstyled run of text — which mattered most for emptying the trash, whose prompt carries a
 * second paragraph about notes other people shared with you.
 *
 * Mounted only while the question is open (render it behind a state flag), so it animates in and
 * traps focus for exactly as long as it's asked. Escape and a backdrop press both cancel; the
 * confirm button takes initial focus so Enter answers it.
 *
 * `tone="danger"` colours the confirm button for irreversible actions (purging the trash); the
 * default reads as a neutral primary action.
 */
export function ConfirmDialog({
  title,
  body,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  tone = 'default',
  busy = false,
  onConfirm,
  onCancel,
}: {
  title: string;
  /** The explanation under the title. A node, so a prompt can carry more than one paragraph. */
  body?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: 'default' | 'danger';
  /** Disables both buttons while the confirmed action is in flight. */
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const panelRef = useRef<HTMLDivElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  useFocusTrap(panelRef);

  useEffect(() => {
    confirmRef.current?.focus();
  }, []);

  // Own Escape handler: this can open from inside the note editor, whose window-level Escape would
  // otherwise close and save the note underneath the question being asked.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== 'Escape') return;
      e.stopPropagation();
      onCancel();
    }
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [onCancel]);

  return (
    <div
      className="fade-in fixed inset-0 z-[70] grid place-items-start overflow-y-auto bg-scrim p-4 pt-[18vh] backdrop-blur-sm"
      // Stop the press reaching a parent overlay — the editor's backdrop saves and closes on it.
      onMouseDown={(e) => {
        e.stopPropagation();
        onCancel();
      }}
    >
      <div
        ref={panelRef}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        onMouseDown={(e) => e.stopPropagation()}
        className="pop-in mx-auto w-full max-w-sm rounded-2xl border border-border-subtle bg-elevated p-5 elev-overlay"
      >
        <h2 id="confirm-dialog-title" className="text-base font-semibold text-text">
          {title}
        </h2>
        {body && <div className="mt-2 space-y-2 text-sm text-text-muted">{body}</div>}

        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={busy}
            className="focus-ring rounded-lg border border-border-strong px-3.5 py-2 text-sm font-medium text-text-muted transition hover:bg-surface-hover hover:text-text disabled:opacity-50"
          >
            {cancelLabel}
          </button>
          <button
            ref={confirmRef}
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className={cn(
              'focus-ring rounded-lg px-3.5 py-2 text-sm font-semibold transition disabled:opacity-50',
              tone === 'danger'
                ? 'bg-danger-bg text-danger hover:brightness-110'
                : 'bg-accent text-black hover:bg-accent-strong',
            )}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
