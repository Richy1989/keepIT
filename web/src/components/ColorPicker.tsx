import { NOTE_COLORS } from '../features/notes/palette';
import { CheckIcon } from './icons';

/** A row of note-color swatches. Calls `onPick` with the color key (e.g. "rose", "default"). */
export function ColorPicker({
  value,
  onPick,
}: {
  value: string | null | undefined;
  onPick: (key: string) => void;
}) {
  const current = value ?? 'default';
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {NOTE_COLORS.map((c) => {
        const selected = current === c.key;
        return (
          <button
            key={c.key}
            type="button"
            title={c.label}
            aria-label={c.label}
            onClick={(e) => {
              e.stopPropagation();
              onPick(c.key);
            }}
            className="focus-ring grid size-6 place-items-center rounded-full border transition hover:scale-110"
            style={{
              backgroundColor: c.key === 'default' ? 'transparent' : c.bg,
              borderColor: selected ? 'var(--color-accent)' : c.border,
            }}
          >
            {selected && <CheckIcon className="text-[11px] text-text" />}
          </button>
        );
      })}
    </div>
  );
}
