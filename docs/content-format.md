# Content format: Benbow Document v1 (ADR-015)

Canonical storage for entry bodies (and template bodies). It is based on the **Portable Text** spec (MIT, github.com/portabletext/portabletext) plus the documented extensions below.
Markdown, plain text, HTML and PDF are **exports** (lossy except HTML). This file is the contract: change it only together with a format-version bump and a reader for the old version.

## Envelope
```json
{"v":1,"blocks":[ ... ]}
```
- `v` is the format version. A reader that sees `v` greater than it supports, or unknown envelope fields, opens the document **read-only** and never re-saves it.
- `blocks` is a flat array (PT style). Nesting is expressed by `level` and by a shared container `_key` (callouts).

## Text block (`_type: "block"`)
| Field | Values | Notes |
|---|---|---|
| `_key` | 8-char base62, unique in the document | **Stable identity.** When a paragraph splits, the first half keeps the key. When two merge, the first key survives. Pasted blocks get new keys |
| `style` | `normal` · `h1` · `h2` · `h3` · `blockquote` | Semantic type. Visuals come from the theme |
| `listItem` | `bullet` · `number` · `check` | optional |
| `level` | 1..6 | list nesting (PT convention, 1-based). Only with `listItem` |
| `checked` | bool | only with `listItem: "check"` |
| `align` | `start` · `center` · `end` | optional, default `start` |
| `callout` | `{"_key": "...", "tone": "<token>", "icon": "<emoji>"}` | optional. Consecutive blocks with the same callout `_key` render as one box. Orthogonal to `style` and `listItem` |
| `children` | spans | at least one. An empty block holds one empty span |
| `markDefs` | annotation objects | only defs that are referenced |

### Spans
`{"_type":"span","text":"...","marks":["strong","m1"]}`. A soft line break inside a block is **U+2028** (LINE SEPARATOR). `\n` never appears in span text, because the editor model treats every `\n` as a block boundary; the decoder maps a stray `\n` to U+2028. Otherwise text is stored exactly as typed: no Unicode normalization, no trimming.

### Decorators (string marks)
`strong`, `em`, `underline`, `strike-through`, `code`, `spoiler`. Names follow PT conventions where they exist.

### Annotations (`markDefs`, referenced by `_key` from `marks`)
| `_type` | Fields | |
|---|---|---|
| `link` | `href` | external URL |
| `entryLink` | `entryId`, `blockKey?` | link to another entry or a specific paragraph |
| `color` | `token` | text color from the palette |
| `highlight` | `token` | background highlight from the palette |
| `size` | `step`: `small` · `large` · `xlarge` | relative text size. Headings are styles, not sizes |

## Other block types
| `_type` | Fields | Editor behaviour |
|---|---|---|
| `code` | `_key`, `language?`, `code` (string, may contain `\n`), `callout?` | monospace region with background. Plain text only (no marks). The editor merges directly adjacent code blocks with the same callout |
| `divider` | `_key`, `variant?` (`line` · `dots` · `wave`) | atomic placeholder line |
| `attachment` | `_key`, `ref` (attachment id), `width?` (0..1), `caption?` | **later** (inline media). v1 media lives in the entry gallery |
| any unknown | preserved verbatim | atomic read-only chip. Never dropped or altered |

**Unknown-content rule:** a block with an unknown `_type`, an unknown field, an unknown mark/markDef or an invalid value is kept **verbatim as an unknown block** (read-only chip). The rest of the document stays editable. Structurally broken JSON (no `_key`/`_type`, not an object) fails decoding, and the revision history is the fallback.
Later atomic embeds (tables, toggles) are new `_type`s that follow the same rules.

## Theme tokens
Colors, highlights and callout tones are **token names**, never raw hex. The design system maps each token to light, dark and per-note-background values. Initial palette (final names set in the design pass): `gray, brown, orange, yellow, green, teal, blue, purple, pink, red`. An unknown token renders as the default color, and its data is kept.

## Canonical serialization (hashing, diffs, merges)
- UTF-8 JSON, fixed key order per object type (order as in the tables above, `_key` and `_type` first), no insignificant whitespace, **exactly one block per line**:
  ```
  {"v":1,"blocks":[
  {"_key":"k3VbQ1aZ","_type":"block","style":"h1","children":[{"_type":"span","text":"Утро","marks":[]}],"markDefs":[]},
  {"_key":"p0Lm77Qx","_type":"divider"}
  ]}
  ```
  Line-based diff3 therefore merges at the paragraph level for free (sync.md), and Markdown/JSON mirror diffs stay readable.
- Normalization (idempotent): merge adjacent spans with identical marks, drop empty spans except in an empty block, sort marks (decorators alphabetically, then annotation keys in first-use order), keep only referenced markDefs, renumber markDef keys per block as `m1, m2, ...` in first-use order. Remove fields equal to their default (`align:"start"`).
- `body_sha256` = SHA-256 of the canonical bytes.
- Tests (property-based): `decode(encode(doc)) == doc`, `canon(canon(x)) == canon(x)`, every `_key` is unique, unknown types and fields round-trip byte-identically.

## Derived forms
- **Plain text** (search index, previews, notifications): block texts joined by `\n`. List and check prefixes are omitted. Code included. Spoiler text is excluded from previews and notifications but indexed for search.
- **Markdown export**, two flavours:
  - *rich* (default, Obsidian-friendly): `**`, `*`, `<u>`, `~~`, `` ` ``, spoiler → `<span class="spoiler">`, color/size → `<span style>`, highlight → `==x==` (default token) or `<mark style>`, align → HTML wrapper, callout → `> [!note] <icon>`, check → `- [ ]` / `- [x]`, entryLink → relative link to the exported file.
  - *clean*: drop all styling that CommonMark+GFM can't express.
  Entry metadata → YAML front matter.
- **HTML export:** lossless, CSS generated from the palette (light theme by default).
- **Import** (Markdown/HTML → Document): supported constructs map back. Anything else becomes a preserved unknown block (`_type: "raw"`, `format`, `source`).
