import { useCallback, useRef, useState } from 'react';
import { useDismissNotification, useNotifications, useRespondToShare } from './queries';
import { useDismiss } from '../../lib/useDismiss';
import { cn } from '../../lib/cn';
import { BellIcon, XIcon } from '../../components/icons';
import type { UserNotificationDto } from '../../api/types';

/**
 * Bell in the top bar with an unread badge and a dropdown of the caller's notifications. Share
 * invites offer Accept / Decline; plain system messages are dismiss-only. Realtime keeps the list
 * live — `RealtimeSync` invalidates the notifications query on a `notification` push.
 */
export function NotificationsBell() {
  const { data: notifications } = useNotifications();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useDismiss(ref, open, close);

  const items = notifications ?? [];
  const count = items.length;

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        aria-label="Notifications"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="focus-ring relative grid size-9 place-items-center rounded-lg text-text-muted transition hover:bg-surface-hover hover:text-text"
      >
        <BellIcon className="text-lg" />
        {count > 0 && (
          <span className="absolute -right-0.5 -top-0.5 grid min-w-4 place-items-center rounded-full bg-accent px-1 text-[10px] font-semibold leading-4 text-black">
            {count > 9 ? '9+' : count}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 z-40 mt-2 w-80 overflow-hidden rounded-xl border border-border-subtle bg-elevated shadow-lg shadow-black/30">
          <div className="border-b border-border-subtle px-3 py-2.5">
            <p className="text-sm font-medium text-text">Notifications</p>
          </div>
          {count === 0 ? (
            <p className="px-3 py-6 text-center text-sm text-text-faint">You're all caught up.</p>
          ) : (
            <ul className="max-h-[70vh] divide-y divide-border-subtle overflow-y-auto">
              {items.map((n) => (
                <li key={n.id}>
                  <NotificationRow notification={n} />
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

/** A dot whose color reflects the notification severity. */
function SeverityDot({ severity }: { severity: string }) {
  const color =
    severity === 'error' ? 'bg-red-500' : severity === 'warning' ? 'bg-amber-500' : 'bg-sky-500';
  return <span className={cn('mt-1.5 size-2 shrink-0 rounded-full', color)} aria-hidden />;
}

/** Renders one notification: a share invite (accept/decline) or a plain dismissible message. */
function NotificationRow({ notification: n }: { notification: UserNotificationDto }) {
  const respond = useRespondToShare();
  const dismiss = useDismissNotification();
  const busy = respond.isPending || dismiss.isPending;

  if (n.type === 'ShareInvite') {
    return (
      <div className="flex gap-2.5 px-3 py-3">
        <SeverityDot severity={n.severity} />
        <div className="min-w-0 flex-1">
          <p className="text-sm text-text">
            <span className="font-medium">{n.sharedByUserEmail ?? 'Someone'}</span> wants to share{' '}
            <span className="font-medium">{n.sharedNoteTitle || 'a note'}</span> with you
            {n.role ? ` as ${n.role.toLowerCase()}` : ''}.
          </p>
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              disabled={busy || !n.id}
              onClick={() => n.id && respond.mutate({ id: n.id, accept: true })}
              className="focus-ring rounded-md bg-accent px-3 py-1 text-xs font-medium text-black transition hover:opacity-90 disabled:opacity-50"
            >
              Accept
            </button>
            <button
              type="button"
              disabled={busy || !n.id}
              onClick={() => n.id && respond.mutate({ id: n.id, accept: false })}
              className="focus-ring rounded-md border border-border-strong px-3 py-1 text-xs text-text-muted transition hover:text-text disabled:opacity-50"
            >
              Decline
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex items-start gap-2.5 px-3 py-3">
      <SeverityDot severity={n.severity} />
      <p className="min-w-0 flex-1 text-sm text-text">{n.notificationText}</p>
      <button
        type="button"
        aria-label="Dismiss"
        disabled={busy || !n.id}
        onClick={() => n.id && dismiss.mutate(n.id)}
        className="focus-ring -mt-0.5 grid size-6 shrink-0 place-items-center rounded-full text-text-faint transition hover:bg-black/20 hover:text-text disabled:opacity-50"
      >
        <XIcon className="text-sm" />
      </button>
    </div>
  );
}
