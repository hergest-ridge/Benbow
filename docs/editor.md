# Editor

The editor is the heart of the product and the biggest technical risk. It is gated by a spike (checklist below). The storage format is in `content-format.md`.

## Requirements
- WYSIWYG. The user never sees markup.
- One seamless text surface: select-all, copy and selection across headings, lists, quotes and callouts work like a notebook. No rigid blocks (anti-Notion/Anytype).
- Native IME behaviour (Russian, autocorrect, swipe typing on Samsung Keyboard and Gboard), undo/redo, fast open (<100 ms from cache), no typing lag.
- Formatting toolbar above the keyboard, Markdown-style shortcuts while typing (`# `, `- `, `1. `, `[] `, `> `, `**x**`) that turn into formatting and hide the syntax, `/` slash menu for inserts.
- It must never destroy content it doesn't understand (unknown blocks become atomic read-only chips).

## Formatting scope v1 (ADR-017)
- **Inline:** bold, italic, underline, strikethrough, inline code, highlight (palette), text color (palette), size steps (small/large/xlarge), spoiler, link, link to another entry or paragraph.
- **Blocks:** headings 1–3, alignment (start/center/end), quote, callout (icon + tone), checklist, bullet and numbered lists with nesting, divider, code block.
- **Later (atomic embeds):** inline media with size, tables, toggles/collapsible sections.

## Approach (ADR-006, gated by the spike)
Use one `BasicTextField(TextFieldState)` per entry body, with **Compose 1.12 rich text editing**: `TextFieldBuffer.addStyle(SpanStyle | ParagraphStyle)`, `getSpanStyles()` / `getParagraphStyles()` returning `TrackedRange`, and `TextFieldState.textStyles`. It works the same on Android, Desktop and Web.
- **Model first.** The editor keeps a parallel semantic model (blocks with `_key`, attributes, marks), updated through `InputTransformation` and edit callbacks. Visual `SpanStyle`/`ParagraphStyle` values are *derived* from it through `BenbowTheme`. Use custom annotations if the API allows. Otherwise use a range registry.
- **Text mapping:** every block = one or more Compose paragraphs carrying the same block key. Enter in a normal block splits it. Shift+Enter and Enter inside a code block insert `\n` within the block. Atomic blocks (divider, unknown, later embeds) are a single U+FFFC in their own paragraph, deleted as a whole.
- **Decorations** are drawn with `drawBehind` / `drawWithContent` using `TextLayoutResult` line metrics: bullets, numbers and checkboxes in a gutter (space reserved via `textIndent`), callout boxes and quote bars across a block range, code backgrounds, divider art, and an animated spoiler mask (Telegram-like particles, revealed on tap). Checkbox and spoiler taps are hit-tested.
- **Fallbacks** if the spike fails: (1) fork compose-rich-editor (1.0.0-rc14, `RichTextState`), (2) Android-only `EditText` + Spannables plus a separate desktop/web implementation, (3) a ComposeTextEditor-style custom text engine. WebView + ProseMirror/TipTap is rejected (not native on Android, needs bundled Chromium on desktop).

## Pipelines
`Document (JSON) ⇄ DocModel` lives in `shared/core` (codec, canonicalizer, plain-text and Markdown/HTML exporters, Markdown import). It is pure Kotlin and property-tested. `DocModel ⇄ TextFieldState` lives in `shared/ui/editor`. Saving serializes the model 1:1, with no parsing round-trip.

## Copy, paste, export
- Copy puts plain text + HTML (rendered with the palette) on the clipboard. Pasting between Benbow editors uses a custom MIME type carrying the JSON fragment (lossless). Extra actions: "Copy as Markdown" and "Copy as plain text".
- Pasting HTML from browsers or other apps converts it to the Document model. Pasting Markdown-looking text offers "paste formatted".
- Partial export: selection → share as text, image card, Markdown or HTML.

## Long documents
Diary entries are small, so one text field is fine. The writer module (P3) may need chunked virtualization. Decide then.

## Ink layer (P2, design now, build later)
- Strokes are vectors `(x, y, pressure, t)` + tool (pen, marker, brush, eraser) + color token + width. They're stored per entry in a separate versioned `ink_layer` (own revisions), never inside the Document.
- **Paper mode:** content is laid out at a fixed logical width (e.g. 400 units) with bundled fonts at a fixed size, so layout is deterministic on every device. Strokes use page coordinates and the whole page scales uniformly. This is the default for ink-heavy entries.
- **Anchored mode** (now possible thanks to block `_key`s): stroke groups anchored to `(blockKey, offset in em units, x as a fraction of width)`, so margin doodles follow their paragraph when the layout reflows.
- Paper feel: grain/noise via `RuntimeShader` (Android 13+, Skia on desktop/web), optional ruling aligned to line height.
- Export: HTML and PDF render ink. Markdown export gets an SVG file next to it.

## Spike checklist (Phase 1, Desktop + Android S24+, then Web)
1. Toolbar toggles for all inline marks, with correct active state for the selection (incl. color/highlight tokens).
2. Headings, quote, callout, lists and checklists with gutter and box drawing. Tapping a checkbox toggles it.
3. Enter/Backspace at list and callout boundaries (continue list, exit on empty item, unindent, leave callout).
4. Markdown shortcuts while typing.
5. Select-all → copy → paste into another app (plain and HTML) and back into Benbow (lossless).
6. Russian IME on Samsung Keyboard and Gboard: composition, autocorrect, swipe typing, no style bleeding at span edges.
7. Undo/redo including style and block-type changes.
8. Model stays consistent: random edit fuzzing keeps `_key`s unique and the document canonical.
9. 20k-char document: no dropped frames while typing on the S24+, open <100 ms.
10. Desktop shortcuts (Ctrl+B/I/U/K/Z/Y). Web IME sanity check.
Adopt if ≥8/10 pass on Android and Desktop. Otherwise evaluate the fallbacks in order.
