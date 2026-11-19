import { ClockIcon } from '../../components/icons';
import { cn } from '../../lib/cn';
import { isOverdue, reminderChipLabel } from './reminderTime';
import type { NoteDto } from '../../api/types';

/**
 * The always-visible reminder indicator on a card: clock, then the local time and how it repeats
 * ("Sun, Nov 29, 09:00 · Monthly"), cut with an ellipsis when the column is narrower than that —
 * the whole of it is in the tooltip. Turns amber once the time has passed (or a one-time reminder
 * has fired). Renders nothing when the note has no reminder; clicking it opens the reminder picker.
 *
 * `inTabOrder={false}` on a card: there the hover toolbar, whose "Remind me" does the same, takes
 * the chip's place while the card holds the focus, so a chip in the tab order would be focused
 * unseen.
 */
export function ReminderChip({
  note,
  onClick,
  inTabOrder = true,
}: {
  note: NoteDto;
  onClick: () => void;
  inTabOrder?: boolean;
}) {
  if (note.remindAtUtc == null) return null;

  const past = note.reminderFired || isOverdue(note.remindAtUtc);
  const label = reminderChipLabel(note.remindAtUtc, note.reminderRecurrence);

  return (
    <button
      type="button"
      tabIndex={inTabOrder ? undefined : -1}
      title={past ? `${label} (passed) — click to reschedule` : `${label} — click to change`}
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      className={cn(
        'focus-ring flex min-w-0 items-center gap-1 rounded-full border px-2 py-0.5 text-xs transition hover:bg-overlay-hover',
        // Filled rather than outlined, and muted rather than faint: on the lift a faint label
        // falls under 3:1 on the darker note colours (index.css.test.ts).
        past
          ? 'border-warning/40 text-warning'
          : 'border-transparent bg-overlay-lift text-text-muted hover:text-text',
      )}
    >
      <ClockIcon className="shrink-0 text-sm" />
      <span className="truncate">{label}</span>
    </button>
  );
}
