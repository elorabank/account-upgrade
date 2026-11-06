#!/usr/bin/env python3
"""Writes docs/hld.html from docs/hld.md, so the two never drift apart.

    python3 docs/build-hld-html.py

docs/hld.md is the source: edit it, then run this. No libraries needed; it converts the Markdown this document uses
(headings, paragraphs, lists, pipe tables, inline code, bold, links, Mermaid blocks). Diagrams are drawn in the
browser by Mermaid (loaded from a CDN), so the page needs an internet connection to show them.
"""
import datetime
import glob
import html
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, "hld.md")
TARGET = os.path.join(HERE, "hld.html")
MERMAID = "https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.esm.min.mjs"

# ADR-0013 -> adr/0013-....md, so every mention links to its record
ADR_FILES = {os.path.basename(p)[:4]: "adr/" + os.path.basename(p) for p in glob.glob(os.path.join(HERE, "adr", "[0-9]*.md"))}


def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def inline(text):
    """Escapes the text, then applies `code`, **bold**, [links](...) and ADR-NNNN links; code is left untouched."""
    parts = re.split(r"(`[^`]+`)", text)
    out = []
    for part in parts:
        if part.startswith("`") and part.endswith("`") and len(part) > 1:
            out.append("<code>" + html.escape(part[1:-1]) + "</code>")
            continue
        s = html.escape(part, quote=False)
        s = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", s)
        s = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", lambda m: f'<a href="{html.escape(m.group(2))}">{m.group(1)}</a>', s)
        s = re.sub(r"\bADR-(\d{4})\b",
                   lambda m: f'<a href="{ADR_FILES[m.group(1)]}">ADR-{m.group(1)}</a>' if m.group(1) in ADR_FILES else m.group(0), s)
        out.append(s)
    return "".join(out)


def table(lines):
    rows = [[c.strip() for c in line.strip().strip("|").split("|")] for line in lines]
    header, align_row, body = rows[0], rows[1], rows[2:]
    align = ["center" if a.startswith(":") and a.endswith(":") else "right" if a.endswith(":") else "" for a in align_row]

    def cell(tag, value, i):
        style = f' style="text-align:{align[i]}"' if i < len(align) and align[i] else ""
        return f"<{tag}{style}>{inline(value)}</{tag}>"

    head = "".join(cell("th", v, i) for i, v in enumerate(header))
    rows_html = "".join("<tr>" + "".join(cell("td", v, i) for i, v in enumerate(r)) + "</tr>" for r in body)
    # A table without a header row (empty header cells) is a key-value block
    thead = "" if all(not h for h in header) else f"<thead><tr>{head}</tr></thead>"
    return f'<div class="table-wrap"><table>{thead}<tbody>{rows_html}</tbody></table></div>'


def convert(markdown):
    lines = markdown.splitlines()
    blocks, toc, title = [], [], ""
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        if line.startswith("```"):
            lang = line[3:].strip()
            code = []
            i += 1
            while i < len(lines) and not lines[i].startswith("```"):
                code.append(lines[i])
                i += 1
            i += 1
            body = html.escape("\n".join(code))
            if lang == "mermaid":
                blocks.append(f'<figure class="figure"><pre class="mermaid">{body}</pre></figure>')
            else:
                blocks.append(f"<pre><code>{body}</code></pre>")
            continue
        heading = re.match(r"(#{1,3}) (.+)", line)
        if heading:
            level, text = len(heading.group(1)), heading.group(2).strip()
            if level == 1:
                title = text
            else:
                anchor = slug(text)
                if level == 2:
                    toc.append((anchor, text))
                blocks.append(f'<h{level} id="{anchor}">{inline(text)}</h{level}>')
            i += 1
            continue
        if line.lstrip().startswith("|"):
            rows = []
            while i < len(lines) and lines[i].lstrip().startswith("|"):
                rows.append(lines[i])
                i += 1
            blocks.append(table(rows))
            continue
        item = re.match(r"(\s*)(-|\d+\.) (.*)", line)
        if item:
            ordered = item.group(2) != "-"
            items = []
            while i < len(lines):
                m = re.match(r"(\s*)(-|\d+\.) (.*)", lines[i])
                if m and not m.group(1):
                    items.append(m.group(3))
                    i += 1
                elif lines[i].startswith("  ") and lines[i].strip() and items:
                    items[-1] += " " + lines[i].strip()   # continuation of the item above
                    i += 1
                else:
                    break
            tag = "ol" if ordered else "ul"
            blocks.append(f"<{tag}>" + "".join(f"<li>{inline(t)}</li>" for t in items) + f"</{tag}>")
            continue
        paragraph = []
        while i < len(lines) and lines[i].strip() and not re.match(r"(#{1,3} |```|\s*\||\s*(-|\d+\.) )", lines[i]):
            paragraph.append(lines[i].strip())
            i += 1
        blocks.append(f"<p>{inline(' '.join(paragraph))}</p>")
    return title, toc, "\n".join(blocks)


TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Account Upgrade HLD</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Source+Serif+4:opsz,wght@8..60,500;8..60,650&family=Public+Sans:wght@400;600&family=JetBrains+Mono:wght@400;600&display=swap">
<style>
/* Same look as docs/redo-logs.html: a reading column with a sticky contents rail on wide screens. */
:root {
  --paper: #f7f8fa; --sheet: #ffffff; --ink: #1b2430; --muted: #5b6676; --rule: #dfe3ea;
  --accent: #1f5fa8; --accent-soft: #e5eef9; --code-bg: #eef1f5;
  --font-display: "Source Serif 4", Georgia, "Times New Roman", serif;
  --font-body: "Public Sans", system-ui, -apple-system, "Segoe UI", sans-serif;
  --font-mono: "JetBrains Mono", ui-monospace, Menlo, Consolas, monospace;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --paper: #12161c; --sheet: #191e26; --ink: #e5e9ef; --muted: #9aa5b4; --rule: #2b323d;
    --accent: #7fb2ee; --accent-soft: #1d2c40; --code-bg: #20262f; color-scheme: dark;
  }
}
:root[data-theme="dark"] {
  --paper: #12161c; --sheet: #191e26; --ink: #e5e9ef; --muted: #9aa5b4; --rule: #2b323d;
  --accent: #7fb2ee; --accent-soft: #1d2c40; --code-bg: #20262f; color-scheme: dark;
}
body {
  margin: 0; background: var(--paper); color: var(--ink); font-family: var(--font-body); font-size: 1rem; line-height: 1.65;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px));
  padding-block: 0 64px;
}
.page { max-width: 1240px; margin: 0 auto; display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 48px; }
@media (max-width: 900px) { .page { grid-template-columns: minmax(0, 1fr); gap: 0; } .toc { display: none; } }
.toc { position: sticky; top: 24px; align-self: start; padding-top: 112px; font-size: 0.9rem; }
.toc p { font-size: 0.75rem; letter-spacing: 0.08em; text-transform: uppercase; color: var(--muted); margin: 0 0 8px; }
.toc ol { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; border-left: 1px solid var(--rule); }
.toc a { display: block; padding: 2px 0 2px 12px; color: var(--muted); text-decoration: none; }
.toc a:hover, .toc a:focus-visible { color: var(--accent); }
main { min-width: 0; max-width: 900px; }
header.lead { padding-block: 56px 24px; border-bottom: 1px solid var(--rule); margin-bottom: 8px; }
.eyebrow { font-family: var(--font-mono); font-size: 0.78rem; letter-spacing: 0.06em; color: var(--accent); margin: 0 0 12px; }
h1 { font-family: var(--font-display); font-weight: 650; font-size: 2.1rem; line-height: 1.15; margin: 0 0 16px; text-wrap: balance; }
h2 { font-family: var(--font-display); font-weight: 650; font-size: 1.5rem; line-height: 1.25; margin: 48px 0 12px; scroll-margin-top: 24px; }
h3 { font-weight: 600; font-size: 1.2rem; margin: 28px 0 8px; scroll-margin-top: 24px; }
p, li { max-width: 72ch; }
.standfirst { font-size: 1.05rem; color: var(--muted); margin: 0; }
a { color: var(--accent); }
strong { font-weight: 600; }
code { font-family: var(--font-mono); font-size: 0.86em; background: var(--code-bg); padding: 1px 5px; border-radius: 4px; overflow-wrap: anywhere; }
ul, ol { padding-left: 22px; display: grid; gap: 4px; }
.figure { margin: 24px 0; background: var(--sheet); border: 1px solid var(--rule); border-radius: 10px; padding: 16px; overflow-x: auto; }
.figure pre.mermaid { margin: 0; background: none; padding: 0; font-family: var(--font-body); text-align: center; }
.figure pre.mermaid svg { max-width: 100%; height: auto; }
.table-wrap { overflow-x: auto; margin: 16px 0; border: 1px solid var(--rule); border-radius: 10px; background: var(--sheet); }
table { border-collapse: collapse; width: 100%; font-size: 0.9rem; }
th, td { text-align: left; padding: 9px 14px; border-bottom: 1px solid var(--rule); vertical-align: top; }
th { font-weight: 600; background: var(--code-bg); }
tr:last-child td { border-bottom: none; }
pre { background: var(--code-bg); border-radius: 8px; padding: 14px 16px; overflow-x: auto; font-family: var(--font-mono); font-size: 0.84rem; }
footer { margin-top: 56px; padding-top: 16px; border-top: 1px solid var(--rule); font-size: 0.85rem; color: var(--muted); }
:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
@media print { .toc { display: none; } .page { display: block; } body { background: #fff; } .figure, .table-wrap { break-inside: avoid; } }
</style>
</head>
<body>
<div class="page">
  <nav class="toc" aria-label="Contents">
    <p>Contents</p>
    <ol>
__TOC__
    </ol>
  </nav>
  <main>
    <header class="lead">
      <p class="eyebrow">ACCOUNT UPGRADE PLATFORM · HIGH-LEVEL DESIGN</p>
      <h1>__TITLE__</h1>
      <p class="standfirst">The nightly upgrade of bank accounts, from the Excel file to the welcome email and the audit trail.
      Generated from <code>docs/hld.md</code> on __DATE__: edit that file, then run <code>python3 docs/build-hld-html.py</code>.</p>
    </header>
__BODY__
    <footer>Source: <a href="hld.md">docs/hld.md</a> · Decisions: <a href="adr/README.md">docs/adr</a> · Generated __DATE__</footer>
  </main>
</div>
<script type="module">
  import mermaid from "__MERMAID__";
  const dark = document.documentElement.dataset.theme === "dark"
    || (document.documentElement.dataset.theme !== "light" && matchMedia("(prefers-color-scheme: dark)").matches);
  mermaid.initialize({ startOnLoad: false, theme: dark ? "dark" : "neutral", securityLevel: "strict",
    fontFamily: getComputedStyle(document.body).fontFamily });
  await mermaid.run({ querySelector: "pre.mermaid" });
</script>
</body>
</html>
"""


def main():
    title, toc, body = convert(open(SOURCE, encoding="utf-8").read())
    short_title = re.sub(r"^Account Upgrade Platform:\s*", "", title)
    toc_html = "\n".join(f'      <li><a href="#{a}">{inline(t)}</a></li>' for a, t in toc)
    page = (TEMPLATE.replace("__TOC__", toc_html)
            .replace("__TITLE__", html.escape(short_title[:1].upper() + short_title[1:]))
            .replace("__BODY__", body)
            .replace("__DATE__", datetime.date.today().isoformat())
            .replace("__MERMAID__", MERMAID))
    with open(TARGET, "w", encoding="utf-8") as f:
        f.write(page)
    diagrams, tables = body.count('class="mermaid"'), body.count("<table")
    print(f"wrote {os.path.relpath(TARGET)}: {len(toc)} sections, {diagrams} diagrams, {tables} tables")


if __name__ == "__main__":
    main()
