import ReactMarkdown, { type Components } from 'react-markdown';
import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';

/** Drops react-markdown's `node` prop so the rest can spread onto a DOM element. */
function dom<P extends { node?: unknown }>(props: P): Omit<P, 'node'> {
  const { node, ...rest } = props;
  void node;
  return rest;
}

/**
 * Element styling for note bodies: compact spacing tuned for cards and the editor preview, GFM
 * extras (strikethrough, task lists), and links that open in a new tab without triggering the
 * card's onClick. Raw HTML in the source is not rendered (react-markdown's default), so shared
 * note content can't inject markup.
 */
const components: Components = {
  p: (p) => <p className="my-1 first:mt-0 last:mb-0" {...dom(p)} />,
  h1: (p) => <h1 className="my-1.5 text-base font-semibold text-text first:mt-0" {...dom(p)} />,
  h2: (p) => <h2 className="my-1.5 text-[15px] font-semibold text-text first:mt-0" {...dom(p)} />,
  h3: (p) => <h3 className="my-1 text-sm font-semibold text-text first:mt-0" {...dom(p)} />,
  h4: (p) => <h4 className="my-1 text-sm font-semibold text-text first:mt-0" {...dom(p)} />,
  h5: (p) => <h5 className="my-1 text-sm font-semibold text-text first:mt-0" {...dom(p)} />,
  h6: (p) => <h6 className="my-1 text-sm font-semibold text-text first:mt-0" {...dom(p)} />,
  ul: (p) =>
    p.className?.includes('contains-task-list') ? (
      <ul className="my-1 list-none pl-1 first:mt-0 last:mb-0" {...dom(p)} />
    ) : (
      <ul className="my-1 list-disc pl-5 first:mt-0 last:mb-0" {...dom(p)} />
    ),
  ol: (p) => <ol className="my-1 list-decimal pl-5 first:mt-0 last:mb-0" {...dom(p)} />,
  li: (p) => <li className="my-0.5" {...dom(p)} />,
  a: (p) => (
    <a
      target="_blank"
      rel="noopener noreferrer"
      onClick={(e) => e.stopPropagation()}
      className="text-accent underline decoration-accent/50 hover:decoration-accent"
      {...dom(p)}
    />
  ),
  code: (p) => (
    <code className="rounded bg-black/25 px-1 py-0.5 font-mono text-[0.85em]" {...dom(p)} />
  ),
  pre: (p) => (
    <pre
      className="my-1.5 overflow-x-auto rounded-md bg-black/25 p-2 [&>code]:bg-transparent [&>code]:p-0"
      {...dom(p)}
    />
  ),
  blockquote: (p) => (
    <blockquote className="my-1.5 border-l-2 border-border-strong pl-3 text-text-muted" {...dom(p)} />
  ),
  hr: (p) => <hr className="my-2 border-border-strong" {...dom(p)} />,
  input: (p) => <input className="mr-1.5 align-middle accent-accent" {...dom(p)} />,
};

/** Renders a note body's Markdown (the note "rich text" format, shared with the Android app). */
export function Markdown({ text, className }: { text: string; className?: string }) {
  return (
    <div className={className ?? 'break-words text-sm leading-relaxed text-text/90 [overflow-wrap:anywhere]'}>
      {/* remark-breaks keeps single newlines as line breaks, so pre-Markdown notes render as written. */}
      <ReactMarkdown remarkPlugins={[remarkGfm, remarkBreaks]} components={components}>
        {text}
      </ReactMarkdown>
    </div>
  );
}
