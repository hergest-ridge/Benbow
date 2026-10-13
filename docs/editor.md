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

## Spike findings (2026-10-12, Compose 1.12.1, S24+ with Gboard)
Code: `shared/core/.../text` (model, edit rules, shortcuts, property tests) and `shared/ui/.../editor`.
- **The model must be the source of truth.** `TrackedRange` objects only live within one `TextFieldBuffer` scope, so they can't be kept between edits, and there are no public custom annotations. The built-in undo stores text only, so it would break formatting. We keep our own `RichText` model + our own undo, and apply edits through `InputTransformation` (`changes` / `ChangeList` is still `@ExperimentalFoundationApi`; a prefix/suffix diff is the fallback).
- **Styles are rendered in `OutputTransformation`.** Compose recomputes it through `derivedStateOf`, so model and theme changes restyle automatically.
- **Paragraph styles and `\n`:** Compose starts a layout paragraph at every ParagraphStyle boundary *and* at every `\n`, which leaves an extra empty line between styled paragraphs. Fix: the displayed text replaces each `\n` with a zero-width glyph U+200B (1:1 offsets) and appends U+2060 after an empty last paragraph. Consequences, all handled: (a) Compose copies the *displayed* text. Wrapping `LocalClipboard` **crashes on Android**: the text toolbar requires the platform `AndroidClipboard`. Replacing the menu's Cut/Copy items would push them into the overflow, because order follows the component list. So the default menu stays, and `EditorClipboardFix` repairs the clip right after a copy: on Android an `OnPrimaryClipChangedListener` that is armed only during or just after a range selection, so other apps' clips are never read and no toast appears; on desktop a `LocalClipboard` wrapper (safe there). Ctrl+C/X copy model text directly. Web is still a TODO. Verified: Select all → Copy → paste keeps paragraphs; (b) a tap right of a line end lands after the glyph, so `fixCursorAfterTap` moves the cursor back. (c) Compose also hands the **IME the displayed text** (`state.visualText`): without a fix the keyboard misses paragraph starts (no auto-caps after Enter) and may treat a glyph as part of a word, so committing a suggestion could merge paragraphs. `EditorImeBridge` (Android) uses `InterceptPlatformTextInput` to wrap the `InputConnection` and `EditorInfo` initial text, so the IME sees real `
` (same length, offsets valid). Verified: Gboard auto-capitalizes after the on-screen Enter. Desktop and web bridges are TODO.
- **Verified on device:** every mark and block style renders; checkboxes, bullets, numbers, quote bar, callout box, code box; Enter continues lists/callouts and exits on an empty item; `# `/`## ` and `**x**` shortcuts (text after the shortcut isn't bold); Gboard composition works.
- **Performance** (S24+, non-debuggable `benchmark` build, typing 40 chars, after warm-up): ~1k chars p50 5 ms / p90 9 ms; ~5k chars p50 5–7 ms / p90 15–16 ms; ~20k chars p50 ~20 ms / p90 ~30 ms (janky). Cold start ~0.2 s. Without styles, 20k gives p50 7–8 ms: the cost is a full relayout of all styled spans on every keystroke, because Compose text layout isn't incremental (unlike Android `DynamicLayout`). Grouping equal-styled paragraphs (now done) saves little. **Conclusion:** fine for diary entries (≤5k typical). Long-form writing (writer module, P3) needs a chunked editor or an `EditText`-based engine (ADR-006).
- **Owner tested by hand** (2026-10-12): typing, keyboards, toolbar, checkboxes, undo, copy/paste: "everything works".
- **Pending (later polish):** desktop run, web IME and clipboard, paragraph spacing (the layout is tight now), End key on desktop (may land after the glyph).

## Pipelines
Implemented: `app.benbow.core.content` (`DocumentCodec`, `toDocument()`/`toRichText()`, `toPlainText()`, `MarkdownExporter`). Atoms (divider, attachment, unknown block) live in the model as a one-char paragraph (U+FFFC). As built (7.2): atoms render over their own line (the paragraph style makes the line exactly as tall; the object character is transparent) through `AtomViews`: the app draws pictures (videos by preview, sounds and files as a row), the editor has a look for dividers and unknown blocks. Edit rules in `core/text/Atoms.kt`: typing just before or after an atom goes onto its own line; Backspace or Delete across the break after an atom (or on its character) removes it whole; across the break before one only an empty line above goes; any other edit leaves an atom paragraph holding exactly its character and drops stray U+FFFC. Removing a picture from the text never removes the attachment.
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
