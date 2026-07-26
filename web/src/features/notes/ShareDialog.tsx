import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  useCreateShare,
  useNoteShares,
  useRevokeShare,
  useUpdateShareRole,
} from './shareQueries';
import { EyeIcon, PencilIcon, XIcon } from '../../components/icons';
import { cn } from '../../lib/cn';
import { useFocusTrap } from '../../lib/useFocusTrap';
import type { NoteDto, NoteRole } from '../../api/types';

/**
 * Owner-only dialog to manage a note's collaborators: invite by email at a role, and see/adjust/
 * remove existing collaborators. Inviting creates a pending invite the recipient must accept; it
 * won't appear in the list here until they do.
 */
export function ShareDialog({ note, onClose }: { note: NoteDto; onClose: () => void }) {
  const { data: shares } = useNoteShares(note.id);
  const createShare = useCreateShare(note.id);
  const updateRole = useUpdateShareRole(note.id);
  const revoke = useRevokeShare(note.id);

  const [email, setEmail] = useState('');
  const [role, setRole] = useState<NoteRole>('Viewer');
  const panelRef = useRef<HTMLDivElement>(null);
  useFocusTrap(panelRef);

  function submit(e: FormEvent) {
    e.preventDefault();
    const trimmed = email.trim();
    if (!trimmed) return;
    createShare.mutate(
      { email: trimmed, role },
      { onSuccess: () => setEmail('') },
    );
  }

  // Own Escape handler: this dialog renders inside the editor's subtree, so without one the editor's
  // window-level Escape would close *and save* the whole note mid-invite.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-[60] grid place-items-start overflow-y-auto bg-black/60 p-4 pt-[12vh] backdrop-blur-sm"
      // Stop the press bubbling into the editor's backdrop, whose onMouseDown saves and closes it.
      onMouseDown={(e) => {
        e.stopPropagation();
        onClose();
      }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="share-dialog-title"
        onMouseDown={(e) => e.stopPropagation()}
        className="mx-auto w-full max-w-md rounded-2xl border border-border-subtle bg-elevated p-5 shadow-2xl shadow-black/60"
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 id="share-dialog-title" className="text-base font-semibold text-text">
            Share note
          </h2>
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            className="focus-ring grid size-7 place-items-center rounded-full text-text-muted transition hover:bg-surface-hover hover:text-text"
          >
            <XIcon className="text-base" />
          </button>
        </div>

        <form onSubmit={submit} className="flex flex-col gap-2">
          <div className="flex gap-2">
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="Email address"
              autoFocus
              className="focus-ring min-w-0 flex-1 rounded-lg border border-border-subtle bg-surface px-3 py-2 text-sm text-text placeholder:text-text-faint"
            />
            <RolePicker value={role} onChange={setRole} />
          </div>
          <button
            type="submit"
            disabled={createShare.isPending || !email.trim()}
            className="focus-ring self-start rounded-lg bg-accent px-4 py-2 text-sm font-medium text-black transition hover:opacity-90 disabled:opacity-50"
          >
            {createShare.isPending ? 'Sending…' : 'Send invite'}
          </button>
          {createShare.isError && (
            <p className="text-xs text-danger">{(createShare.error as Error).message}</p>
          )}
          {createShare.isSuccess && (
            <p className="text-xs text-text-muted">Invite sent — they'll see it in their notifications.</p>
          )}
        </form>

        <div className="mt-5">
          <p className="mb-2 text-xs font-medium uppercase tracking-wide text-text-faint">
            People with access
          </p>
          <ul className="flex flex-col gap-1">
            <li className="flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm">
              <span className="flex-1 truncate text-text">You</span>
              <span className="text-xs text-text-faint">Owner</span>
            </li>
            {(shares ?? []).map((s) => (
              <li key={s.granteeId} className="flex items-center gap-2 rounded-lg px-2 py-1.5 text-sm">
                <span
                  className={cn('flex-1 truncate', s.pending ? 'text-text-muted' : 'text-text')}
                  title={s.email}
                >
                  {s.email}
                </span>
                {s.pending ? (
                  // No accepted share yet — the role can't be changed until they accept.
                  <span className="text-xs italic text-text-faint">
                    Pending · {(s.role ?? 'Viewer').toLowerCase()}
                  </span>
                ) : (
                  <RolePicker
                    value={s.role}
                    compact
                    onChange={(r) => updateRole.mutate({ granteeId: s.granteeId, role: r })}
                  />
                )}
                <button
                  type="button"
                  aria-label={s.pending ? `Cancel invite for ${s.email}` : `Remove ${s.email}`}
                  title={s.pending ? 'Cancel invite' : 'Remove'}
                  onClick={() => revoke.mutate(s.granteeId)}
                  className="focus-ring grid size-6 place-items-center rounded-full text-text-faint transition hover:bg-black/20 hover:text-text"
                >
                  <XIcon className="text-sm" />
                </button>
              </li>
            ))}
            {(shares?.length ?? 0) === 0 && (
              <li className="px-2 py-1.5 text-xs text-text-faint">Not shared with anyone yet.</li>
            )}
          </ul>
        </div>
      </div>
    </div>
  );
}

/** A small viewer/editor toggle. `compact` renders just the two icons for the collaborator rows. */
function RolePicker({
  value,
  onChange,
  compact = false,
}: {
  value: NoteRole;
  onChange: (r: NoteRole) => void;
  compact?: boolean;
}) {
  const roles: { role: NoteRole; label: string; Icon: typeof EyeIcon }[] = [
    { role: 'Viewer', label: 'Viewer', Icon: EyeIcon },
    { role: 'Editor', label: 'Editor', Icon: PencilIcon },
  ];
  return (
    <div className="flex overflow-hidden rounded-lg border border-border-subtle">
      {roles.map(({ role, label, Icon }) => (
        <button
          key={role}
          type="button"
          title={label}
          onClick={() => onChange(role)}
          className={cn(
            'flex items-center gap-1 px-2 py-1.5 text-xs transition',
            value === role
              ? 'bg-accent/15 text-accent'
              : 'text-text-muted hover:bg-surface-hover hover:text-text',
          )}
        >
          <Icon className="text-sm" />
          {!compact && label}
        </button>
      ))}
    </div>
  );
}
