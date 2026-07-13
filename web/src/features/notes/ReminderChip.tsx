import { ClockIcon, RepeatIcon } from '../../components/icons';
import { cn } from '../../lib/cn';
import { formatReminderTime, isOverdue } from './reminderTime';
import type { NoteDto } from '../../api/types';

/**
 * The always-visible reminder indicator on a card: clock + compact local time, plus a repeat glyph
 * when recurring. Turns amber once the time has passed (or a one-time reminder has fired). Renders
 * nothing when the note has no reminder; clicking it opens the reminder picker.
 */
export function ReminderChip({ note, onClick }: { note: NoteDto; onClick: () => void }) {
  if (note.remindAtUtc == null) return null;

  const past = note.reminderFired || isOverdue(note.remindAtUtc);
  const recurring = note.reminderRecurrence != null && note.reminderRecurrence !== 'None';

  return (
    <button
      type="button"
      title={past ? 'Reminder (passed) — click to reschedule' : 'Reminder — click to change'}
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      className={cn(
        'focus-ring flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs transition hover:bg-black/20',
        past
          ? 'border-amber-400/40 text-amber-400'
          : 'border-border-strong text-text-faint hover:text-text',
      )}
    >
      <ClockIcon className="text-sm" />
      <span>{formatReminderTime(note.remindAtUtc)}</span>
      {recurring && <RepeatIcon className="text-[10px]" />}
    </button>
  );
}
