import ReactMarkdown, { type Components } from 'react-markdown';
import remarkBreaks from 'remark-breaks';
import remarkGfm from 'remark-gfm';

/** Drops react-markdown's `node` prop so the rest can spread onto a DOM element. */
function dom<P extends { node?: unknown }>(props: P): Omit<P, 'node'> {
  const { node, ...rest } = props;
  void node;
  return rest;
}

/** A link target that opens somewhere outside keepIT: a web page or a mail client. */
function isOpenable(href: string | undefined): boolean {
  return !!href && /^(https?|mailto):/i.test(href);
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
  a: (p) => {
    const { href, children, ...rest } = dom(p);
    // react-markdown already blanks dangerous schemes (javascript:, file:). What is left that is
    // not a web or mail address — a blank href, or a relative one like the toolbar's own "url"
    // placeholder — would open keepIT itself in a new tab, so it shows as plain text instead.
    if (!isOpenable(href)) return <span>{children}</span>;
    return (
      <a
        target="_blank"
        rel="noopener noreferrer"
        onClick={(e) => e.stopPropagation()}
        className="text-accent-ink underline decoration-accent-ink/50 hover:decoration-accent-ink"
        href={href}
        {...rest}
      >
        {children}
      </a>
    );
  },
  // The CSP loads images only from keepIT itself, so an image a note points at elsewhere would be
  // a broken-image icon (and, without the CSP, a tracking pixel in a shared note). Its description
  // stands in, linked to the image so it is still one click away.
  img: (p) => {
    const { src, alt } = dom(p);
    const label = alt || 'image';
    return typeof src === 'string' && isOpenable(src) ? (
      <a
        target="_blank"
        rel="noopener noreferrer"
        onClick={(e) => e.stopPropagation()}
        className="text-accent-ink underline decoration-accent-ink/50 hover:decoration-accent-ink"
        href={src}
      >
        {label}
      </a>
    ) : (
      <span>{label}</span>
    );
  },
  code: (p) => (
    <code className="rounded bg-overlay-hover px-1 py-0.5 font-mono text-[0.85em]" {...dom(p)} />
  ),
  pre: (p) => (
    <pre
      className="my-1.5 overflow-x-auto rounded-md bg-overlay-hover p-2 [&>code]:bg-transparent [&>code]:p-0"
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
