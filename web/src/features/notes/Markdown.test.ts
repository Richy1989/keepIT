import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Markdown } from './Markdown';
import { previewSource } from './preview';

/** What a note body's links and images become. Shared notes are other people's text. */
function render(text: string, preview?: number): string {
  return renderToStaticMarkup(createElement(Markdown, { text, preview }));
}

/** What a rendered body reads as, tags gone. */
function textOf(html: string): string {
  return html.replace(/<[^>]+>/g, '');
}

describe('Markdown links', () => {
  it('opens web and mail addresses, bare URLs included', () => {
    const html = render('[a](https://x.example) [m](mailto:x@y.z) see www.example.org');
    expect(html).toContain('href="https://x.example"');
    expect(html).toContain('href="mailto:x@y.z"');
    expect(html).toContain('href="http://www.example.org"');
  });

  it('shows anything else as plain text rather than a link back into keepIT', () => {
    // "url" is the toolbar's placeholder; file: and javascript: come out of react-markdown blank.
    const html = render('[a](url) [b](file:///x) [c](javascript:alert(1))');
    expect(html).not.toContain('<a');
    expect(html).toContain('<span>a</span>');
  });

  it("shows an image as its description, linked, since the CSP won't load it", () => {
    const html = render('![a cat](https://img.example/cat.png)');
    expect(html).not.toContain('<img');
    expect(html).toContain('href="https://img.example/cat.png"');
    expect(html).toContain('>a cat</a>');
  });

  it('shows raw HTML as text', () => {
    expect(render('<b>x</b>')).toContain('&lt;b&gt;x&lt;/b&gt;');
  });
});

describe('a card preview', () => {
  const filler = 'x'.repeat(590);

  it('leaves a short note as it is', () => {
    expect(render('**short** note', 600)).toBe(render('**short** note'));
  });

  it('keeps formatting that goes on past the cut', () => {
    // Cutting the source left "**bold acr…" with no closing marker: raw asterisks on the card.
    const html = render(`${filler} **bold across the cut**`, 600);
    expect(html).toContain('<strong>bold acro…</strong>');
    expect(html).not.toContain('**');
  });

  it('keeps a highlight that goes on past the cut', () => {
    const html = render(`<mark data-color="green">${'y'.repeat(700)}</mark>`, 600);
    expect(html).toContain('data-color="green"');
    expect(html).not.toContain('&lt;mark');
  });

  it('keeps a reference link whose definition is past the cut', () => {
    const html = render(`[site][s] ${'x'.repeat(700)}\n\n[s]: https://x.example`, 600);
    expect(html).toContain('href="https://x.example"');
  });

  it('leaves out what is past the cut, links included, and says so', () => {
    const html = render(`${'x'.repeat(600)}\n\n[later](https://later.example)`, 600);
    expect(html).not.toContain('later');
    expect(textOf(html)).toMatch(/x…$/);
  });

  it('cuts inside a code block without losing the block', () => {
    const html = render(`\`\`\`\n${'z'.repeat(700)}\n\`\`\`\n\nafter`, 600);
    expect(html).toContain('<pre');
    expect(html).toContain('z…');
    expect(html).not.toContain('after');
  });

  it('parses only a window of a long note, and keeps its far definitions', () => {
    const long = `[site][s] start\n\n${'filler line\n\n'.repeat(5000)}[s]: https://x.example`;
    expect(previewSource(long, 600).length).toBeLessThan(1500);
    expect(render(long, 600)).toContain('href="https://x.example"');
  });

  it('closes a code block the window ends inside, so the definitions are not code', () => {
    const long = `[site][s]\n\n\`\`\`\n${'code\n\n'.repeat(2000)}\`\`\`\n\n[s]: https://x.example`;
    expect(previewSource(long, 600)).toMatch(/\n```\n\n\[s\]: https:\/\/x\.example$/);
    expect(render(long, 600)).toContain('href="https://x.example"');
  });

  it('never splits an emoji in half', () => {
    const html = render(`${'a'.repeat(599)}😀 more`, 600);
    expect(html).not.toMatch(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/);
    expect(textOf(html)).toMatch(/a…$/);
  });
});
