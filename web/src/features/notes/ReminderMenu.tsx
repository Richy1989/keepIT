import { useState } from 'react';
import { useSetNoteReminder } from './queries';
import { parseUtc, toLocalInputValue } from './reminderTime';
import type { NoteDto, ReminderRecurrence } from '../../api/types';

type Recurrence = Exclude<ReminderRecurrence, null | undefined>;

const RECURRENCE_LABELS: Record<Recurrence, string> = {
  None: 'Does not repeat',
  Daily: 'Daily',
  Weekly: 'Weekly',
  Monthly: 'Monthly',
  Yearly: 'Yearly',
};

/** Keep-style quick picks. Each returns a local Date. */
function presets(): { label: string; when: Date }[] {
  const laterToday = new Date();
  laterToday.setHours(laterToday.getHours() + 3, 0, 0, 0);
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  tomorrow.setHours(8, 0, 0, 0);
  const nextWeek = new Date();
  nextWeek.setDate(nextWeek.getDate() + 7);
  nextWeek.setHours(8, 0, 0, 0);
  return [
    { label: 'Later today', when: laterToday },
    { label: 'Tomorrow 8:00', when: tomorrow },
    { label: 'Next week', when: nextWeek },
  ];
}

/**
 * The reminder picker panel, rendered inline like the card's color popover (and in the editor's
 * footer). Reminders are per-user — read access suffices, so this is never gated on `canEdit`.
 * Presets apply immediately; the custom datetime applies on Save. Saving always resets a fired
 * reminder back to pending.
 */
export function ReminderMenu({ note, onClose }: { note: NoteDto; onClose: () => void }) {
  const setReminder = useSetNoteReminder();

  // Prefill with the existing reminder, else tomorrow morning.
  const [when, setWhen] = useState(() => {
    if (note.remindAtUtc) return toLocalInputValue(parseUtc(note.remindAtUtc));
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    tomorrow.setHours(8, 0, 0, 0);
    return toLocalInputValue(tomorrow);
  });
  const [recurrence, setRecurrence] = useState<Recurrence>(note.reminderRecurrence ?? 'None');

  function save(at: Date) {
    // `datetime-local` values parse as local time; the wire wants UTC.
    setReminder.mutate({
      id: note.id,
      reminder: { remindAtUtc: at.toISOString(), recurrence },
    });
    onClose();
  }

  function clear() {
    setReminder.mutate({ id: note.id, reminder: null });
    onClose();
  }

  return (
    <div
      onClick={(e) => e.stopPropagation()}
      className="mt-3 space-y-2 rounded-lg border border-border-subtle bg-canvas/60 p-3"
    >
      <div className="flex flex-wrap gap-1.5">
        {presets().map((p) => (
          <button
            key={p.label}
            type="button"
            onClick={() => save(p.when)}
            className="focus-ring rounded-full border border-border-strong px-2.5 py-1 text-xs text-text-muted transition hover:bg-black/20 hover:text-text"
          >
            {p.label}
          </button>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <input
          type="datetime-local"
          value={when}
          onChange={(e) => setWhen(e.target.value)}
          aria-label="Remind me at"
          className="focus-ring rounded-md border border-border-strong bg-transparent px-2 py-1 text-xs text-text [color-scheme:dark]"
        />
        <select
          value={recurrence}
          onChange={(e) => setRecurrence(e.target.value as Recurrence)}
          aria-label="Repeat"
          className="focus-ring rounded-md border border-border-strong bg-transparent px-2 py-1 text-xs text-text-muted"
        >
          {(Object.keys(RECURRENCE_LABELS) as Recurrence[]).map((r) => (
            <option key={r} value={r} className="bg-elevated text-text">
              {RECURRENCE_LABELS[r]}
            </option>
          ))}
        </select>
      </div>

      <div className="flex items-center gap-2">
        <button
          type="button"
          disabled={!when}
          onClick={() => save(new Date(when))}
          className="focus-ring rounded-md bg-accent px-2.5 py-1 text-xs font-medium text-black transition hover:bg-accent-strong disabled:opacity-50"
        >
          Save
        </button>
        {note.remindAtUtc != null && (
          <button
            type="button"
            onClick={clear}
            className="focus-ring rounded-md px-2.5 py-1 text-xs text-text-muted transition hover:bg-black/20 hover:text-text"
          >
            Clear reminder
          </button>
        )}
      </div>
    </div>
  );
}
