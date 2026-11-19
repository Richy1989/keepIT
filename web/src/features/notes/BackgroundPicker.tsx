import type { CSSProperties } from 'react';
import { NOTE_BACKGROUNDS } from './backgroundImages';
import { backgroundVars } from './backgrounds';
import { noteColor } from './palette';
import { CheckIcon, XIcon } from '../../components/icons';

/**
 * The row of background images under the colour swatches: "None", then each background this build
 * ships, drawn on the note's current colour so the choice looks as it will. Renders nothing when
 * the build ships no backgrounds. Calls `onPick` with a name, or null for none.
 */
export function BackgroundPicker({
  value,
  color,
  onPick,
}: {
  value: string | null | undefined;
  color: string | null | undefined;
  onPick: (name: string | null) => void;
}) {
  if (NOTE_BACKGROUNDS.length === 0) return null;
  const swatch = noteColor(color);
  const current = value ?? null;

  const tile = (name: string | null, label: string) => {
    const selected = current === name;
    return (
      <button
        key={name ?? ''}
        type="button"
        title={label}
        aria-label={label}
        aria-pressed={selected}
        onClick={(e) => {
          e.stopPropagation();
          onPick(name);
        }}
        className="focus-ring relative isolate grid size-10 place-items-center overflow-hidden rounded-lg border transition hover:scale-105"
        style={{
          backgroundColor: swatch.bg,
          borderColor: selected ? 'var(--color-accent-ink)' : swatch.border,
        }}
      >
        <NoteTile name={name} color={color} />
        {selected ? (
          <CheckIcon className="text-xs text-text" />
        ) : (
          name === null && <XIcon className="text-xs text-text-faint" />
        )}
      </button>
    );
  };

  return (
    <div className="mt-2 flex flex-wrap items-center gap-1.5 border-t border-overlay-line pt-2">
      {tile(null, 'No background')}
      {NOTE_BACKGROUNDS.map((b) => tile(b.name, b.label))}
    </div>
  );
}

/**
 * A note's background image: a layer behind the content of whatever it is put in, over the note's
 * colour, the pattern painted in that colour's ink. Nothing when the note has none, or names one
 * this build doesn't ship. The parent must be `relative isolate`; the layer covers its padding box.
 */
export function NoteTile({ name, color }: { name: string | null | undefined; color: string | null | undefined }) {
  const vars = backgroundVars(name, noteColor(color).border, NOTE_BACKGROUNDS);
  if (!vars) return null;
  return (
    <div
      aria-hidden
      className="note-tile pointer-events-none absolute inset-0 -z-10"
      style={vars as CSSProperties}
    />
  );
}
