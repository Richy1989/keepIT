import { useRef, useState, type FocusEvent, type KeyboardEvent, type MouseEvent } from 'react';
import { ListGlyph, ListIconPicker } from './ListIconPicker';

/**
 * The sidebar's inline editor for a list's name and icon, used both to create a list and to edit
 * one. The icon button beside the name opens the emoji grid below it; choosing an icon is optional.
 *
 * Enter, or moving the focus out of the editor as a whole, commits; Escape closes the grid first and
 * cancels after that. Moving between the name, the icon button and the grid is still editing, which
 * is why the commit is on the editor's blur rather than the name field's.
 */
export function ListEditor({
  initialName,
  initialIcon,
  placeholder,
  onCommit,
  onCancel,
}: {
  initialName: string;
  initialIcon: string | null;
  placeholder?: string;
  /** The trimmed name (possibly empty: the caller decides what a blank name means) and the icon. */
  onCommit: (name: string, icon: string | null) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(initialName);
  const [icon, setIcon] = useState(initialIcon);
  const [picking, setPicking] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  // Commit and cancel both unmount the editor, and a blur arriving on the way out must not commit
  // a second time.
  const closed = useRef(false);

  function commit() {
    if (closed.current) return;
    closed.current = true;
    onCommit(name.trim(), icon);
  }

  function cancel() {
    if (closed.current) return;
    closed.current = true;
    onCancel();
  }

  return (
    <div
      ref={root}
      onBlur={(e: FocusEvent) => {
        if (!root.current?.contains(e.relatedTarget as Node | null)) commit();
      }}
      onKeyDown={(e: KeyboardEvent) => {
        if (e.key !== 'Escape') return;
        e.preventDefault();
        if (picking) {
          setPicking(false);
          input.current?.focus();
        } else {
          cancel();
        }
      }}
      className="mx-1 flex flex-col gap-1.5"
    >
      <div className="flex items-center gap-1.5">
        <button
          type="button"
          title="List icon"
          aria-label={icon ? `List icon ${icon}` : 'Choose a list icon'}
          aria-expanded={picking}
          // Keeps the focus in the name field, for the reason ListIconPicker gives.
          onMouseDown={(e: MouseEvent) => e.preventDefault()}
          onClick={() => setPicking((p) => !p)}
          className="focus-ring grid size-8 shrink-0 place-items-center rounded-lg border border-border-strong bg-canvas text-lg text-text-faint transition hover:bg-surface-hover hover:text-text"
        >
          <ListGlyph icon={icon} />
        </button>
        <input
          ref={input}
          autoFocus
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e: KeyboardEvent) => {
            if (e.key === 'Enter') commit();
          }}
          placeholder={placeholder}
          aria-label="List name"
          className="focus-ring min-w-0 flex-1 rounded-lg border border-border-strong bg-canvas px-2 py-1.5 text-sm placeholder:text-text-faint"
        />
      </div>
      {picking && (
        <ListIconPicker
          value={icon}
          onPick={(picked) => {
            setIcon(picked);
            setPicking(false);
            // Back to the name, so Enter commits. From the keyboard this also keeps the focus inside
            // the editor as the grid (and the focused emoji in it) unmounts.
            input.current?.focus();
          }}
        />
      )}
    </div>
  );
}
