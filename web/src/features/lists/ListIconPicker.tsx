import type { MouseEvent } from 'react';
import { ListIcon } from '../../components/icons';
import { cn } from '../../lib/cn';
import { LIST_ICONS } from './listIcons';

/**
 * A list's icon where an icon goes: its emoji, or the generic list icon when it has none. Sized like
 * the stroke icons (1em square) so a column of lists lines up whichever each one shows; the emoji is
 * drawn a little smaller than 1em because colour emoji run wider than their font size.
 */
export function ListGlyph({ icon, className }: { icon: string | null | undefined; className?: string }) {
  if (!icon) return <ListIcon className={className} />;
  return (
    <span aria-hidden="true" className={cn('grid size-[1em] place-items-center leading-none', className)}>
      <span className="text-[0.85em]">{icon}</span>
    </span>
  );
}

/**
 * The grid of emoji a list can wear, with "No icon" while it has one. Calls `onPick` with the emoji,
 * or null for none.
 *
 * It sits inside an inline editor whose name field commits when it loses focus, so a mouse press
 * anywhere in here is kept from taking the focus: Safari never focuses a clicked button, so the
 * editor couldn't tell "clicked an emoji" from "clicked away" by where the focus went. Keyboard
 * users still Tab into the grid; the editor treats focus inside it as still editing.
 */
export function ListIconPicker({
  value,
  onPick,
}: {
  value: string | null;
  onPick: (icon: string | null) => void;
}) {
  return (
    <div
      role="group"
      aria-label="List icon"
      onMouseDown={(e: MouseEvent) => e.preventDefault()}
      className="rounded-lg border border-border-subtle bg-canvas/40 p-1.5"
    >
      <div className="mb-1 flex h-6 items-center justify-between px-1">
        <span className="text-xs text-text-faint">Icon</span>
        {value && (
          <button
            type="button"
            onClick={() => onPick(null)}
            className="focus-ring rounded px-1 text-xs text-text-muted transition hover:text-text"
          >
            No icon
          </button>
        )}
      </div>
      <div className="grid grid-cols-8 gap-0.5">
        {LIST_ICONS.map((icon) => (
          <button
            key={icon}
            type="button"
            aria-pressed={value === icon}
            onClick={() => onPick(icon)}
            className={cn(
              'focus-ring grid aspect-square place-items-center rounded text-base leading-none transition hover:bg-surface-hover',
              value === icon && 'bg-accent/15 ring-1 ring-accent-ink/60',
            )}
          >
            {icon}
          </button>
        ))}
      </div>
    </div>
  );
}
