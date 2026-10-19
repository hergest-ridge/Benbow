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
- **Atoms over their lines:** each picture, sound or drawing is composed keyed by its paragraph's key, not its offset, and keeps its last place for the frame the text layout trails an edit; keyed by offset, every letter typed above it rebuilt it (it reloaded and blinked, owner 2026-10-18).
- **Spoilers as built (7.8a, ADR-031):** `Spoilers.kt` in `shared/ui`. Each spoiler range becomes a rectangle per line from the `TextLayoutResult` (a pixel wider, so the span's own tint never peeks out). Read-only text (feed lines, the post view, history) leaves the glyphs out there (`spoilerMask`) and draws dots over it: one 80 dp tile of random points in three drifting, pulsing layers (`ImageShader`, tinted to the text color), in a graphics layer of its own so a frame redraws only the dots; UI tests skip the infinite shimmer. A tap on a hidden spoiler is consumed (a feed card doesn't open) and dissolves all of the entry's spoilers in a circle growing from the tap (550 ms); the state lives with the entry on screen. The editor draws spoiler text at 35% under still dots, and clear where the cursor or selection touches it.
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

## Links (7.15, ADR-038, as built)
- **Model** (`core/text/Links.kt`, `LinkEdits.kt`): only http(s) is recognized in text; known trackers (`utm_*`, `fbclid`, `gclid`, `yclid`, `si` on YouTube and Spotify) go as a link goes in. An address pasted over words the user had selected links them (`overSelection`: a keyboard replacing the word it composes is never read as that); pasted on an empty plain line it becomes a `linkCard` atom; pasted in a sentence it reads short (`habr.com/ru/articles/1`). An address typed and followed by a space or a line break (or committed with its space) is linked as typed; addresses inside pasted text are linked as they stand; `[words](address)` turns into linked words on `)`. Code stays plain.
- **Look** (`ui/editor/LinkViews.kt`): the link's words in the accent color with a thin underline drawn apart (1 dp, 2.5 dp under the baseline, 45 % alpha), not the font's heavy one. A card is a 72 dp row (accent bar, site, two lines of title, a 48 dp picture or the site's letter on a tint) or, with a picture, the big look (picture across up to 280 dp, title, two lines of description). While a page is read the title shimmers.
- **Reading** (posts, the feed, history): a tap opens a link; the press shows first (a soft background, a card sinks to 98 %), and sliding off, scrolling or a long press turns it into nothing (`awaitLinkTap` also watches the text move on screen). A card's long press offers Открыть and Копировать ссылку.
- **Writing**: a tap only places the cursor; a link the cursor was put in (not typed through) gets a bubble above it: its site (opens it), edit, copy, unlink. Ctrl+click opens on desktop and the web; Ctrl+K and the toolbar's link button open the link sheet (address with «Вставить из буфера», the link's words; or «Запись»: an entry by search, `entryLink`). A tap on a card selects it with its bubble: Открыть, Крупно/Строкой, В текст, edit (address and title), delete.
- **Removing** goes down a ladder, each step undoable with «Вернуть» (`EditorState.removal`, a snackbar): card → link in the text (`cardToText`) → words without the link → gone. Backspace or Delete at any atom (card, picture, divider) selects it first (an outline, a card's bubble) and removes it on the second press (owner 2026-10-19).
- **Pages** are read once, when a card appears in an editor (or a shared link's note is saved), never while reading; the result fills the card by its paragraph key without an undo step (a card that comes back unread is read again), or the stored entry as a revision once the editor is gone (`fillStoredCard`). A title written by hand is never replaced.

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
- As built (7.5, ADR-026): **drawings in notes** first. A brush next to the media bar opens the drawing screen (one finger draws, two pan and zoom 1-4x; pen with width from speed, see-through marker, eraser that takes whole strokes, 11 palette colors, 3 sizes, undo/redo, "Больше места" adds half a canvas of height). Leaving it saves an SVG attachment placed at the cursor (or after the text); tapping a drawing while editing opens it again, while reading it opens the viewer. Paper pages and anchored margin marks are still to come.

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
