import { useCallback, useRef, useState, type ReactNode, type RefObject } from 'react';
import { applyMarkdown, type MarkdownAction } from './markdownEdit';
import { HIGHLIGHT_COLORS, HIGHLIGHT_LABELS, type HighlightColor } from './highlight';
import { commit, highlight } from './highlightKey';
import {
  BoldIcon,
  CodeIcon,
  HeadingIcon,
  HighlighterIcon,
  ItalicIcon,
  LinkIcon,
  ListIcon,
  ListOrderedIcon,
  StrikethroughIcon,
  XIcon,
} from '../../components/icons';
import { useDismiss } from '../../lib/useDismiss';

const ACTIONS: { action: MarkdownAction; label: string; icon: ReactNode }[] = [
  { action: 'bold', label: 'Bold', icon: <BoldIcon /> },
  { action: 'italic', label: 'Italic', icon: <ItalicIcon /> },
  { action: 'strike', label: 'Strikethrough', icon: <StrikethroughIcon /> },
  { action: 'heading', label: 'Heading', icon: <HeadingIcon /> },
  { action: 'bullet', label: 'Bullet list', icon: <ListIcon /> },
  { action: 'ordered', label: 'Numbered list', icon: <ListOrderedIcon /> },
  { action: 'link', label: 'Link', icon: <LinkIcon /> },
  { action: 'code', label: 'Code', icon: <CodeIcon /> },
];

/**
 * The Markdown formatting bar over the body textarea: every button rewrites the textarea's current
 * selection via {@link applyMarkdown} (or {@link applyHighlight}) and puts focus (and the adjusted
 * selection) back, so writing flows on uninterrupted. The highlighter opens its five colours and
 * "Remove"; Ctrl/⌘+Shift+H in the textarea highlights in the last colour used (`onHighlightKey`).
 */
export function MarkdownToolbar({
  textareaRef,
  onChange,
}: {
  textareaRef: RefObject<HTMLTextAreaElement | null>;
  onChange: (value: string) => void;
}) {
  function run(action: MarkdownAction) {
    const el = textareaRef.current;
    if (!el) return;
    commit(el, applyMarkdown(el.value, el.selectionStart, el.selectionEnd, action), onChange);
  }

  return (
    <div className="flex items-center gap-0.5" role="toolbar" aria-label="Text formatting">
      {ACTIONS.map(({ action, label, icon }) => (
        <button
          key={action}
          type="button"
          title={label}
          aria-label={label}
          // Mousedown would move focus out of the textarea and collapse its selection.
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => run(action)}
          className="focus-ring grid size-7 place-items-center rounded-md text-sm text-text-muted transition hover:bg-overlay-hover hover:text-text"
        >
          {icon}
        </button>
      ))}
      <HighlightButton textareaRef={textareaRef} onChange={onChange} />
    </div>
  );
}

/** The highlighter: a button that opens the five colours and "Remove highlight". */
function HighlightButton({
  textareaRef,
  onChange,
}: {
  textareaRef: RefObject<HTMLTextAreaElement | null>;
  onChange: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useDismiss(ref, open, close);

  function pick(color: HighlightColor | null) {
    const el = textareaRef.current;
    setOpen(false);
    if (el) highlight(el, color, onChange);
  }

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        title="Highlight (Ctrl+Shift+H)"
        aria-label="Highlight"
        aria-expanded={open}
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => setOpen((o) => !o)}
        className="focus-ring grid size-7 place-items-center rounded-md text-sm text-text-muted transition hover:bg-overlay-hover hover:text-text"
      >
        <HighlighterIcon />
      </button>
      {open && (
        <div
          role="menu"
          aria-label="Highlight colour"
          className="pop-in absolute left-0 top-8 z-20 flex items-center gap-1 rounded-lg border border-border-subtle bg-elevated p-1.5 elev-raised"
        >
          {HIGHLIGHT_COLORS.map((color) => (
            <button
              key={color}
              type="button"
              role="menuitem"
              title={HIGHLIGHT_LABELS[color]}
              aria-label={`Highlight ${HIGHLIGHT_LABELS[color].toLowerCase()}`}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => pick(color)}
              className="focus-ring size-6 rounded-full border border-border-subtle transition hover:scale-110"
              style={{ backgroundColor: `var(--hl-${color})` }}
            />
          ))}
          <button
            type="button"
            role="menuitem"
            title="Remove highlight"
            aria-label="Remove highlight"
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => pick(null)}
            className="focus-ring grid size-6 place-items-center rounded-full text-text-muted transition hover:bg-overlay-hover hover:text-text"
          >
            <XIcon className="text-xs" />
          </button>
        </div>
      )}
    </div>
  );
}
