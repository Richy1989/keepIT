import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Markdown } from './Markdown';

/** What a note body's links and images become. Shared notes are other people's text. */
function render(text: string): string {
  return renderToStaticMarkup(createElement(Markdown, { text }));
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
