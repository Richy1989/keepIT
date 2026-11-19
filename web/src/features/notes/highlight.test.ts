import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Markdown } from './Markdown';
import { applyHighlight, withoutHighlightTags, type HighlightColor } from './highlight';
import cases from './highlight.json';

function decode(html: string): string {
  return html
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#x27;|&#39;/g, "'")
    .replace(/&amp;/g, '&');
}

/** The highlights in rendered HTML, in the order they open, each with its text. */
function marksOf(html: string): { color: string; text: string }[] {
  const found: { color: string; at: number }[] = [];
  const opens = /<mark data-color="(\w+)"[^>]*>/g;
  for (let m = opens.exec(html); m; m = opens.exec(html)) found.push({ color: m[1], at: m.index + m[0].length });
  return found.map(({ color, at }) => {
    // The text up to this mark's own closing tag, counting nested ones.
    let depth = 1;
    const tags = /<(\/?)mark\b[^>]*>/g;
    tags.lastIndex = at;
    let end = html.length;
    for (let t = tags.exec(html); t; t = tags.exec(html)) {
      depth += t[1] ? -1 : 1;
      if (depth === 0) {
        end = t.index;
        break;
      }
    }
    return { color, text: decode(html.slice(at, end).replace(/<[^>]+>/g, '')) };
  });
}

describe('highlight.json: rendering', () => {
  for (const c of cases.render) {
    it(c.source, () => {
      const html = renderToStaticMarkup(createElement(Markdown, { text: c.source }));
      expect(decode(html.replace(/<br\s*\/?>/g, '\n').replace(/<[^>]+>/g, ''))).toBe(c.text);
      expect(marksOf(html)).toEqual(c.marks);
    });
  }
});

describe('highlight.json: the highlighter', () => {
  for (const c of cases.edit) {
    it(`${JSON.stringify(c.value)} with ${c.color}`, () => {
      const start = c.value.indexOf('«');
      const end = c.value.indexOf('»') - 1;
      const plain = c.value.replace('«', '').replace('»', '');
      const edit = applyHighlight(plain, start, end, c.color as HighlightColor | null);
      const shown =
        edit.value.slice(0, edit.selectionStart) +
        '«' +
        edit.value.slice(edit.selectionStart, edit.selectionEnd) +
        '»' +
        edit.value.slice(edit.selectionEnd);
      expect(shown).toBe(c.result);
    });
  }
});

describe('highlighted list items', () => {
  it('stay list items, each with its own highlight', () => {
    const edit = applyHighlight('- one\n- [ ] two', 0, 15, 'green');
    const html = renderToStaticMarkup(createElement(Markdown, { text: edit.value }));
    expect(html.match(/<li/g)).toHaveLength(2);
    expect(marksOf(html)).toEqual([
      { color: 'green', text: 'one' },
      { color: 'green', text: 'two' },
    ]);
  });
});

describe('withoutHighlightTags', () => {
  it('leaves the text', () => {
    expect(withoutHighlightTags('a <mark data-color="green">b</mark> c')).toBe('a b c');
  });
});
