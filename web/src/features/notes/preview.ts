/**
 * A card's preview of a note body: the start of the note as the full view renders it, cut after
 * about `budget` characters of text.
 *
 * The cut is made in the parsed tree, after every other plugin, never in the source. Cutting the
 * source changed how the *start* of a card read: a bold run, a highlight or a code block that went
 * on past the cut lost its closing marker and showed raw asterisks or tags, and a reference link
 * whose definition sat further down showed as plain brackets. In the tree those are already
 * resolved, so the preview shows the same formatting as the note, and only nodes are dropped or
 * shortened. Link and footnote definitions are always kept, wherever they are, since they render
 * nothing themselves and the links that remain need them.
 *
 * What is left is what renders, so a link past the cut is not in the page at all — not merely
 * hidden, where the keyboard would still reach it.
 *
 * Parsing costs time in proportion to the source, and a body may hold 100,000 characters (about
 * 600 ms a card), so a card parses only a window of it: {@link previewSource}.
 */

import { openCodeFence } from './markdownEdit';

interface MdNode {
  type: string;
  value?: string;
  children?: MdNode[];
}

const ELLIPSIS = '…';
/** Nodes that render nothing where they stand, but that the nodes left in the preview may need. */
const DEFINITIONS = new Set(['definition', 'footnoteDefinition']);

/**
 * The part of [text] a preview of about [budget] characters needs parsed: up to the first blank
 * line past two budgets in (four at most, for one huge paragraph), plus the link and footnote
 * definitions from the rest. Emphasis, highlights and links never cross a blank line, so what the
 * preview shows renders as in the whole note; where markup is dense, the window holds less than a
 * budget of text and the preview is merely shorter. A code block the window ends inside is closed first, or the
 * definitions would read as code.
 */
export function previewSource(text: string, budget: number): string {
  const soft = budget * 2;
  const hard = budget * 4;
  if (text.length <= soft) return text;
  const blank = text.slice(soft, hard).search(/\n[ \t]*\n/);
  const cut = blank === -1 ? hard : soft + blank;
  if (cut >= text.length) return text;
  const head = text.slice(0, cut);
  const definitions = text.slice(cut).match(/^ {0,3}\[[^\]\n]+\]:[ \t]*\S.*$/gm);
  if (!definitions) return head;
  const fence = openCodeFence(head, head.length);
  return `${head}${fence ? `\n${fence}` : ''}\n\n${definitions.join('\n')}`;
}

/** The remark plugin: `remarkPlugins={[…, [remarkPreview, { budget: 600 }]]}`. Runs last. */
export function remarkPreview(options: { budget: number }) {
  return (tree: MdNode) => trimTree(tree, options.budget);
}

/**
 * Trims [root] in place to about [budget] characters. Text counts its characters; a line break, a
 * rule or an image counts one, as its newline or alt text roughly did in the source.
 */
export function trimTree(root: MdNode, budget: number): void {
  let left = budget;
  let done = false;
  let cutText = false;
  let droppedAny = false;
  let lastText: MdNode | null = null;
  const rescued: MdNode[] = [];

  const rescue = (node: MdNode) => {
    if (DEFINITIONS.has(node.type)) rescued.push(node);
    else node.children?.forEach(rescue);
  };

  /** Shortens [node] to what fits; false when it is dropped. */
  const take = (node: MdNode): boolean => {
    if (DEFINITIONS.has(node.type)) return true;
    if (done) {
      droppedAny = true;
      rescue(node);
      return false;
    }
    if (typeof node.value === 'string') {
      const value = node.value;
      if (value.length > left) {
        let at = left;
        // Never split an emoji, or any other character outside the BMP, in half.
        const code = value.charCodeAt(at - 1);
        if (code >= 0xd800 && code <= 0xdbff) at--;
        node.value = value.slice(0, at) + ELLIPSIS;
        cutText = true;
        done = true;
      } else {
        left -= value.length;
        done = left === 0;
      }
      lastText = node;
      return true;
    }
    if (node.children) {
      node.children = node.children.filter(take);
      return true;
    }
    left -= 1;
    done = left <= 0;
    return true;
  };

  root.children = (root.children ?? []).filter(take);
  root.children.push(...rescued);
  // The budget ran out exactly at the end of a text, with more to come: the ellipsis still says so.
  const last = lastText as MdNode | null;
  if (droppedAny && !cutText && last) last.value += ELLIPSIS;
}
