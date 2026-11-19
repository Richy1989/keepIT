# Improve Markdown editing in keepIT

**Proposal for review · 19 November 2026**

Ship two independent fixes first: predictable formatting commands on both clients and structurally correct web card previews. Keep the note body as Markdown text throughout. Evaluate a new web editor after those fixes, when the comparison can focus on what writing feels like. Android table layout is a separate reading improvement.

## Starting point

| Surface | Current implementation | Why it matters |
| --- | --- | --- |
| Stored notes | `keepIT/keepITCore/Data/Note.cs` stores the body as a string. Checklist items have a separate model. | These changes can ship without an API or data migration. |
| Web | `web/src/features/notes/` contains the textarea editor, toolbar commands, and a react-markdown viewer with remark-gfm, remark-breaks, and a custom highlight plugin. The composer and modal both handle Enter. | Changing the renderer alone leaves keyboard and toolbar behavior intact. |
| Android | `app/app/src/main/java/org/hyperstarit/keepitapp/ui/markdown/` uses commonmark-java for display and live source styling. Kotlin commands rewrite text and selection. | The existing parser exposes source positions. Cards, the viewer, and plain-text previews depend on this code. |

The initial review ran 46 focused web tests successfully and reproduced the four command defects below by calling the web editing functions. Android behavior was inspected in source. Reproduce each case on Android before assigning it as an Android defect.

## 1. Make editing commands predictable

Start with failing fixtures in Vitest and Kotlin/JUnit. Follow the shared-file pattern in `web/src/features/notes/highlight.json` and Android’s `HighlightMarksTest.kt`. Extend the highlight fixtures in place. Put the other new cases in one shared editing fixture. Each case supplies the command, source and selection before the edit, and expected source and selection afterward. Use the existing «selection» convention, with «» for a caret.

| Operation | Input or steps | Required result |
| --- | --- | --- |
| Start a bullet list | Tap Bullets on an empty note or at the start of a blank line. | Insert `- ` with the caret after the space. Enter on that empty item exits the list. Blank separator lines inside an existing multiline selection stay blank. |
| Format a partial multiline selection | In “one two” followed by “three four”, select “two” through “three” and tap Bold. | Produce `one **two**` followed by `**three** four`. Keep the selection anchored to the edited words so a second tap restores the original. Also cover a selection ending at column zero of the next line. |
| Highlight list items | Select two entire bullet items and apply green. | Each line has the form `- <mark data-color="green">word</mark>`. Assert that rendering yields two list items with highlighted labels. Cover task markers, indentation, blank lines and partially selected endpoints. |
| Enter in fenced code | Type Enter after a line beginning with `- example` inside a fenced block. | Insert an ordinary newline. Cover backtick and tilde fences, a fence still open while typing, and a real list immediately after a closed fence. Keep existing task and ordered-list continuation cases green. |

Implement in `markdownEdit.ts`, `highlight.ts`, `MarkdownEdit.kt` and `HighlightMarks.kt` as needed. Keep the current selection and native input handling at the UI boundary. For list continuation, check the surrounding Markdown block before rewriting a candidate line. Prefer parser context already available on the client. Compare the cost and accuracy of any additional parsing, particularly during Android typing. Test real Enter events in both web entry points and an Android text field, including composition, paste and selection replacement.

**Completion check.** Run the focused Vitest suites from `web` with `npm test -- markdownEdit.test.ts highlight.test.ts Markdown.test.ts` and the Android JVM suite from `app` with `./gradlew :app:testMinifiedUnitTest`. Exercise the four examples through the editor UI. Record which checks ran on a device or emulator.

## 2. Fix web card previews

`web/src/features/notes/NoteCard.tsx` slices the raw body at 600 UTF-16 units before handing it to `Markdown.tsx`. A closing delimiter or link definition past the cutoff can change how the *beginning* of the card reads.

Parse the complete source with the existing plugins, then limit what the card displays. Start by measuring full rendering with a compact visual limit. If rendering every hidden block costs too much, trim the parsed tree after resolving formatting and references. Preserve compact cards, functioning visible links, and the current handling of raw HTML and external images. Check keyboard navigation for links outside the visible preview.

**Completion check.** Compare the visible start of a card with the full viewer for bold, a colored highlight, and a reference link whose definition falls beyond character 600. Check mobile and desktop widths. Record before/after grid render and scroll measurements for the same mix of short and long notes on the same browser and hardware. Decide whether the simpler full-render approach is viable from those results. This change can ship independently of the editor work.

## 3. Decide what writing should feel like

Prototype CodeMirror 6 in the web note modal on an isolated branch. Keep the current renderer for reading. Give the prototype a narrow purpose: style Markdown as the user types and evaluate whether source editing feels natural for a quick note. Use the Markdown extension’s list and URL-paste commands where they satisfy the shared fixtures. Keep one handler per command so Enter cannot run twice. [CodeMirror Markdown package](https://github.com/codemirror/lang-markdown#api-reference)

Compare the current editor and prototype on the same tasks: start a list, highlight one word, paste a URL over selected text, undo once, then save and reopen on Android. Check touch selection, IME composition, keyboard-only operation, body limits, and initial editor load. Opening and closing without an edit must preserve the source exactly. Editing one word must preserve unrelated text, single line breaks, highlight tags and literal HTML.

**Decision.** Adopt CodeMirror only if people can complete these tasks more easily and compatibility checks pass. A good question to ask after the trial is “Would you want to see these formatting characters while writing?” If visible syntax itself is the objection, prototype direct rich-text editing with Tiptap instead. Its Markdown bridge parses into a document model and serializes back, so run the same exact-source checks on representative existing notes before considering rollout. If neither prototype improves the experience enough, ship phases 1 and 2 on their own. [Tiptap Markdown support and limitations](https://tiptap.dev/docs/editor/markdown)

## 4. Optional Android reading improvement

If tables are a priority, use the existing parsed table rows and cells to render a real table in the full-note viewer. Keep compact summaries on cards and plain text in widgets and reminders. Check narrow screens, long cell values, accessible row and column navigation, and horizontal scrolling on wide tables. Scope this separately from editor selection and formatting fixes.

## Questions for the reviewer

1. Can either platform produce a counterexample to the proposed selection and list rules? Check nested lists, a selection ending at a newline, and an unfinished fence inside a quoted list item.
2. Does the proposed card strategy preserve resolved links, keyboard focus and bounded grid cost? What test would reveal a regression?
3. Which interaction would actually benefit from a new editor after the fixes ship? Would the CodeMirror trial answer that question, or is a rich-text prototype the more informative first experiment?
