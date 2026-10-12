# Editor

The editor is the heart of the product and the biggest technical risk. It is gated by a spike (checklist below).

## Requirements
- WYSIWYG. The user never sees or edits Markdown syntax.
- One seamless text surface: select-all, copy and selection across headings, lists and quotes work like a notebook. No rigid blocks (anti-Notion/Anytype).
- Native IME behaviour (Russian, autocorrect, Gboard swipe typing), undo/redo, fast open (<100 ms from cache), no typing lag.
- Formatting toolbar above the keyboard, Markdown shortcuts while typing (`# `, `- `, `1. `, `[] `, `> `, `**x**`) that turn into formatting and hide the syntax, `/` slash menu for inserts.
- It must never destroy content it doesn't understand.

## Approach (proposed)
Use one `BasicTextField(TextFieldState)` per entry body, with **Compose 1.12 rich text editing**: `TextFieldBuffer.addStyle(SpanStyle | ParagraphStyle)`, `getSpanStyles()` / `getParagraphStyles()` returning `TrackedRange`, and `TextFieldState.textStyles`. It works the same on Android, Desktop and Web.
- Semantics live in our own model, not in visual styles. Each mark and block type maps to a style through `BenbowTheme`, so re-theming never touches content. Use custom annotations if the API allows. Otherwise keep a parallel range registry synced through `InputTransformation`.
- Bullets, numbers and checkboxes are visual-only: `ParagraphStyle.textIndent` makes room, and they're drawn in a gutter with `drawBehind` using `TextLayoutResult` line metrics. Checkbox taps are hit-tested on the gutter.
- Fallbacks if the spike fails: (1) fork compose-rich-editor (1.0.0-rc14, `RichTextState`, MD import/export), (2) Android-only `EditText` + Spannables with a separate desktop/web implementation, (3) a library like ComposeTextEditor. A WebView + ProseMirror/TipTap editor is rejected: not native on Android, and it needs a bundled Chromium on desktop.

## Document model (`shared/core`)
`RichDoc(blocks)`. Block = `Paragraph | Heading(1..3) | Quote | Bullet(level) | Ordered(level, start) | Task(level, checked) | CodeBlock(lang) | Divider | AttachmentRef(id) | Opaque(rawMd)`.
Inline runs carry marks `Bold | Italic | Strike | Code | Highlight | Link(url)`.
Pipelines: `Markdown ⇄ RichDoc` (codec in `shared/core`, pure, heavily tested) and `RichDoc ⇄ TextFieldState` (in `shared/ui/editor`). **Storage is Markdown only.** RichDoc is transient.

> **Under review:** the owner rejected Markdown-limited formatting. ADR-015 proposes a Portable Text-based JSON as canonical, with Markdown as an export. The sections below (subset, round-trip) will be rewritten once ADR-015 is confirmed.

## Markdown subset (canonical serialization)
| Element | Syntax emitted |
|---|---|
| Bold / italic / strike / inline code | `**x**` / `*x*` / `~~x~~` / `` `x` `` |
| Highlight | `==x==` (Obsidian-compatible) |
| Link | `[text](url)` |
| Heading 1–3 | `#`, `##`, `###` |
| Bullet / ordered / task | `- `, `1. `, `- [ ] ` / `- [x] `, 2-space indent per level |
| Quote | `> ` |
| Code block | fenced ```` ``` ```` with optional lang |
| Divider | `---` |
| Attachment | `![caption](benbow:attachment/<id>)`, rewritten to relative paths on export |
| Empty paragraph (intentional spacing) | `&nbsp;` line (exact rule decided in the spike) |
Not supported in v1: colors, font sizes, underline, alignment, tables. Tables and HTML from imports are kept as `Opaque` read-only chips, so they are preserved byte-for-byte.
Entry metadata is **not** in the body. It lives in DB fields and becomes YAML front matter on export.

## Round-trip rules (tested with property tests)
- `parse(serialize(doc)) == normalize(doc)` for random docs.
- Markdown that hasn't been touched stays byte-identical after load → save. The serializer emits canonical form only for edited blocks, or we accept canonicalization once on first save (decided in the spike).
- Special characters are escaped. A corpus of tricky samples lives in `shared/core` test resources.

## Copy, paste, export
- Copy puts plain text + HTML (+ Markdown via a custom MIME on desktop) on the clipboard. "Copy as Markdown" is a separate action.
- Pasting HTML (web pages) converts it to the subset. Pasting Markdown-looking text offers "paste formatted".
- Partial export: selection → share as text, image card or Markdown.

## Attachments in text (open question)
v1 proposal: an entry-level media gallery (Telegram-style album above or below the text). Inline images are a later spike: a placeholder char + tall `ParagraphStyle.lineHeight` + image drawn over the reserved line.

## Long documents
Diary entries are small, so one text field is fine. The writer module (P3) may need chunked virtualization. Decide then.

## Ink layer (P2, design now, build later)
- Strokes are vectors `(x, y, pressure, t)` + tool (pen, marker, brush, eraser) + color token + width. They're stored per entry in a separate versioned `ink_layer` (own revisions), never inside the Markdown.
- **Paper mode (recommended):** ink is allowed only on entries in paper mode. Content is laid out at a fixed logical width (e.g. 400 units) with bundled fonts at a fixed size, so layout is deterministic on every device. Strokes use page coordinates and the whole page scales uniformly. This is what makes ink "reliable" despite adaptive layouts.
- The alternative, anchoring strokes to blocks (block id + em offsets), needs stable block IDs that Markdown doesn't have. Rejected for now.
- Paper feel: grain/noise via `RuntimeShader` (Android 13+, Skia on desktop/web), optional ruling aligned to line height.
- Export: Markdown ignores ink. HTML and PDF render it. The ink is also exported as an SVG file next to the Markdown.

## Spike checklist (Phase 1, Android + Desktop, then Web)
1. Toolbar toggles for bold/italic/strike/highlight/link, with correct active state for the selection.
2. Headings, quote, lists and tasks with gutter drawing. Tapping a checkbox toggles it.
3. Enter/Backspace at list boundaries (continue list, exit on empty item, unindent).
4. Markdown shortcuts while typing.
5. Select-all → copy → paste into another app (plain and rich).
6. Russian IME on Samsung Keyboard and Gboard: composition, autocorrect, swipe typing, no style bleeding at span edges.
7. Undo/redo including style changes.
8. Round-trip: load MD → edit → save keeps untouched parts intact.
9. 20k-char document: no dropped frames while typing on a mid-range device, open <100 ms.
10. Desktop shortcuts (Ctrl+B/I/K/Z/Y). Web IME sanity check.
Adopt if ≥8/10 pass on Android and Desktop. Otherwise evaluate the fallbacks in order.
