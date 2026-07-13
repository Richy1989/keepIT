import { useMemo, useState, type ReactNode } from 'react';
import { useSetNoteReminder } from './queries';
import { parseUtc } from './reminderTime';
import { ClockIcon } from '../../components/icons';
import { cn } from '../../lib/cn';
import type { NoteDto, ReminderRecurrence } from '../../api/types';

type Recurrence = Exclude<ReminderRecurrence, null | undefined>;

const RECURRENCE_LABELS: Record<Recurrence, string> = {
  None: 'Does not repeat',
  Daily: 'Daily',
  Weekly: 'Weekly',
  Monthly: 'Monthly',
  Yearly: 'Yearly',
};

const pad = (n: number) => String(n).padStart(2, '0');
const toDateInput = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const toTimeInput = (d: Date) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;

/** The hero line: "Today, 14:00" / "Tomorrow, 08:00" / "Mon, Jul 21, 09:00". */
function heroLabel(at: Date): string {
  const today = new Date();
  const tomorrow = new Date();
  tomorrow.setDate(today.getDate() + 1);
  const time = at.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  if (at.toDateString() === today.toDateString()) return `Today, ${time}`;
  if (at.toDateString() === tomorrow.toDateString()) return `Tomorrow, ${time}`;
  const opts: Intl.DateTimeFormatOptions = { weekday: 'short', month: 'short', day: 'numeric' };
  if (at.getFullYear() !== today.getFullYear()) opts.year = 'numeric';
  return `${at.toLocaleDateString(undefined, opts)}, ${time}`;
}

/** The live caption under the hero: how far away it is, plus the repeat cadence when set. */
function captionLabel(at: Date, recurrence: Recurrence): string {
  const minutes = Math.floor((at.getTime() - Date.now()) / 60_000);
  const hours = Math.floor(minutes / 60);
  const distance =
    minutes < 0
      ? 'already passed — fires right away'
      : minutes < 1
        ? 'in under a minute'
        : minutes < 60
          ? `in ${minutes} min`
          : minutes < 48 * 60
            ? `in ${hours} ${hours === 1 ? 'hour' : 'hours'}`
            : `in ${Math.floor(minutes / (60 * 24))} days`;
  return recurrence === 'None'
    ? distance
    : `${distance} · repeats ${RECURRENCE_LABELS[recurrence].toLowerCase()}`;
}

/**
 * The reminder picker panel, rendered inline like the card's color popover (and in the editor's
 * footer) — the web twin of the Android bottom sheet. Its hero is the *answer*: the resolved fire
 * time in words over a live caption; quick chips and the date/time/repeat fields all feed that one
 * line, and a single accent button confirms. Reminders are per-user — read access suffices, so
 * this is never gated on `canEdit`. Saving always resets a fired reminder back to pending.
 */
export function ReminderMenu({ note, onClose }: { note: NoteDto; onClose: () => void }) {
  const setReminder = useSetNoteReminder();

  // Prefill with the existing reminder, else tomorrow morning.
  const initial = useMemo(() => {
    if (note.remindAtUtc) return parseUtc(note.remindAtUtc);
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    tomorrow.setHours(8, 0, 0, 0);
    return tomorrow;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [date, setDate] = useState(() => toDateInput(initial));
  const [time, setTime] = useState(() => toTimeInput(initial));
  const [recurrence, setRecurrence] = useState<Recurrence>(note.reminderRecurrence ?? 'None');

  // Quick picks are pinned at open so the chips don't drift while the panel sits there.
  const presets = useMemo(() => {
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
  }, []);

  const selected = date && time ? new Date(`${date}T${time}`) : null;
  const isPast = selected != null && selected.getTime() < Date.now();

  function save() {
    if (!selected) return;
    // Inputs parse as local time; the wire wants UTC.
    setReminder.mutate({
      id: note.id,
      reminder: { remindAtUtc: selected.toISOString(), recurrence },
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
      className="mt-3 space-y-3 rounded-xl border border-border-subtle bg-canvas/70 p-3"
    >
      {/* Hero: the resolved fire time, in words — every control below feeds this line. */}
      <div className="flex items-center gap-2.5">
        <ClockIcon className="shrink-0 text-lg text-accent" />
        <div className="min-w-0">
          <p className="truncate text-[15px] font-semibold leading-tight text-text">
            {selected ? heroLabel(selected) : 'Pick a time'}
          </p>
          {selected && (
            <p className={cn('text-xs', isPast ? 'text-amber-400' : 'text-text-faint')}>
              {captionLabel(selected, recurrence)}
            </p>
          )}
        </div>
      </div>

      {/* Quick picks — selecting updates the hero; the button below confirms. */}
      <div className="flex flex-wrap gap-1.5">
        {presets.map((p) => {
          const active = date === toDateInput(p.when) && time === toTimeInput(p.when);
          return (
            <button
              key={p.label}
              type="button"
              onClick={() => {
                setDate(toDateInput(p.when));
                setTime(toTimeInput(p.when));
              }}
              className={cn(
                'focus-ring rounded-full border px-2.5 py-1 text-xs transition',
                active
                  ? 'border-accent bg-accent/15 text-accent'
                  : 'border-border-strong text-text-muted hover:bg-black/20 hover:text-text',
              )}
            >
              {p.label}
            </button>
          );
        })}
      </div>

      <div className="flex gap-2">
        <Field label="Date" className="flex-1">
          <input
            type="date"
            value={date}
            onChange={(e) => setDate(e.target.value)}
            aria-label="Reminder date"
            className="w-full bg-transparent text-xs text-text [color-scheme:dark] focus:outline-none"
          />
        </Field>
        <Field label="Time" className="w-24">
          <input
            type="time"
            value={time}
            onChange={(e) => setTime(e.target.value)}
            aria-label="Reminder time"
            className="w-full bg-transparent text-xs text-text [color-scheme:dark] focus:outline-none"
          />
        </Field>
      </div>

      <Field label="Repeats">
        <select
          value={recurrence}
          onChange={(e) => setRecurrence(e.target.value as Recurrence)}
          aria-label="Repeat"
          className="w-full bg-transparent text-xs text-text focus:outline-none"
        >
          {(Object.keys(RECURRENCE_LABELS) as Recurrence[]).map((r) => (
            <option key={r} value={r} className="bg-elevated text-text">
              {RECURRENCE_LABELS[r]}
            </option>
          ))}
        </select>
      </Field>

      <div className="flex items-center justify-between gap-2 pt-0.5">
        {note.remindAtUtc != null ? (
          <button
            type="button"
            onClick={clear}
            className="focus-ring rounded-md px-2 py-1.5 text-xs text-text-muted transition hover:bg-black/20 hover:text-text"
          >
            Clear reminder
          </button>
        ) : (
          <span />
        )}
        <button
          type="button"
          disabled={!selected}
          onClick={save}
          className="focus-ring rounded-lg bg-accent px-3 py-1.5 text-xs font-medium text-black transition hover:bg-accent-strong disabled:opacity-50"
        >
          {note.remindAtUtc != null ? 'Update reminder' : 'Set reminder'}
        </button>
      </div>
    </div>
  );
}

/** One labeled setting field: a tiny eyebrow over the control, boxed like the Android value cards. */
function Field({
  label,
  children,
  className,
}: {
  label: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <label
      className={cn(
        'flex flex-col gap-0.5 rounded-lg border border-border-subtle bg-black/20 px-2.5 py-1.5 transition focus-within:border-accent/60',
        className,
      )}
    >
      <span className="text-[10px] font-semibold uppercase tracking-wider text-text-faint">
        {label}
      </span>
      {children}
    </label>
  );
}
