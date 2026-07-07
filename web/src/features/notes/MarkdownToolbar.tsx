import type { ReactNode, RefObject } from 'react';
import { applyMarkdown, type MarkdownAction } from './markdownEdit';
import {
  BoldIcon,
  CodeIcon,
  HeadingIcon,
  ItalicIcon,
  LinkIcon,
  ListIcon,
  ListOrderedIcon,
  StrikethroughIcon,
} from '../../components/icons';

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
 * selection via {@link applyMarkdown} and puts focus (and the adjusted selection) back, so writing
 * flows on uninterrupted.
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
    const edit = applyMarkdown(el.value, el.selectionStart, el.selectionEnd, action);
    onChange(edit.value);
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(edit.selectionStart, edit.selectionEnd);
    });
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
          className="focus-ring grid size-7 place-items-center rounded-md text-sm text-text-muted transition hover:bg-black/20 hover:text-text"
        >
          {icon}
        </button>
      ))}
    </div>
  );
}
