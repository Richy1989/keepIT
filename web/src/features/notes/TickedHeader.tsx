import { ChevronDownIcon } from '../../components/icons';
import { cn } from '../../lib/cn';

/**
 * The header a checklist's ticked rows fold away under, as on Android (and in Google Keep): a
 * chevron and how many there are, the chevron where the rows' checkbox is. A click opens or closes
 * them; screen readers hear it as a disclosure button with its expanded state.
 *
 * The rows start folded every time a note opens, as they do on Android: the state is not kept.
 */
export function TickedHeader({
  count,
  open,
  onToggle,
  indent = false,
}: {
  count: number;
  open: boolean;
  onToggle: () => void;
  /** Leaves room for the editor rows' drag grip, so the chevron lines up with their checkbox. */
  indent?: boolean;
}) {
  return (
    <button
      type="button"
      aria-expanded={open}
      onClick={onToggle}
      className="flex w-full items-center gap-1.5 rounded py-1 text-left text-sm text-text-muted transition hover:text-text"
    >
      {indent && <span aria-hidden="true" className="size-4 shrink-0" />}
      <ChevronDownIcon
        aria-hidden="true"
        className={cn('size-4 shrink-0 transition-transform', !open && '-rotate-90')}
      />
      <span>{count === 1 ? '1 ticked item' : `${count} ticked items`}</span>
    </button>
  );
}
