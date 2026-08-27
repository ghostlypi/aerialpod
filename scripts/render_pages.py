"""Render PRIVACY.md to docs/privacy.html, plus a small landing page.

Deliberately produces one self-contained file with no build step and no Jekyll:
a privacy policy has to still be there, and still render, years after anyone
last thought about the toolchain that made it.
"""

from __future__ import annotations

import html
import re
import sys
from pathlib import Path

import markdown

PLACEHOLDER = re.compile(r"FILL-IN-[A-Z-]+|<!--\s*FILL IN")

STYLE = """
:root{
  color-scheme: light dark;
  --ground:#ffffff; --ink:#1a1d21; --soft:#5a636e; --line:#e3e7ec;
  --accent:#2b6fd4; --code:#f2f5f8;
}
@media (prefers-color-scheme: dark){
  :root{ --ground:#101317; --ink:#e6eaef; --soft:#98a3b1; --line:#252c35;
         --accent:#7fb0f5; --code:#181d24; }
}
*{box-sizing:border-box}
body{margin:0;background:var(--ground);color:var(--ink);
  font:16px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,
  "Helvetica Neue",Arial,sans-serif;-webkit-font-smoothing:antialiased}
main{max-width:44rem;margin:0 auto;padding:3rem 1.25rem 6rem}
h1{font-size:1.9rem;line-height:1.2;margin:0 0 .4rem}
h2{font-size:1.25rem;margin:2.4rem 0 .6rem;padding-top:1.2rem;
  border-top:1px solid var(--line)}
h3{font-size:1.02rem;margin:1.6rem 0 .4rem;color:var(--soft)}
p,li{color:var(--ink)}
a{color:var(--accent)}
code{background:var(--code);padding:.12em .35em;border-radius:4px;
  font-size:.9em;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
hr{border:0;border-top:1px solid var(--line);margin:2.5rem 0}
em{color:var(--soft)}
table{border-collapse:collapse;width:100%;margin:1rem 0;display:block;
  overflow-x:auto}
th,td{text-align:left;padding:.55rem .7rem;border-bottom:1px solid var(--line);
  vertical-align:top}
th{font-size:.82rem;text-transform:uppercase;letter-spacing:.04em;
  color:var(--soft);font-weight:600}
blockquote{margin:1rem 0;padding:.6rem 1rem;border-left:3px solid var(--accent);
  background:var(--code);color:var(--soft)}
.back{display:inline-block;margin-bottom:2rem;font-size:.9rem;color:var(--soft);
  text-decoration:none}
.back:hover{color:var(--accent)}
"""

PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{description}">
<meta name="robots" content="index, follow">
<style>{style}</style>
</head>
<body>
<main>
{back}{body}
</main>
</body>
</html>
"""


def render(repo: Path, force: bool) -> int:
    source = repo / "PRIVACY.md"
    if not source.exists():
        print(f"error: {source} not found", file=sys.stderr)
        return 1

    text = source.read_text()
    hits = sorted({m.group(0) for m in PLACEHOLDER.finditer(text)})
    if hits:
        where = ", ".join(hits)
        if not force:
            print(
                f"error: {source.name} still has unfilled placeholders: {where}\n"
                "  Google Play requires a working contact address, and it is shown\n"
                "  publicly on your listing. Fill it in and run again, or pass\n"
                "  --force to preview the styling without publishing.",
                file=sys.stderr,
            )
            return 1
        print(f"WARNING: rendering with unfilled placeholders: {where}",
              file=sys.stderr)
        print("         PREVIEW ONLY — do not publish this.", file=sys.stderr)

    body = markdown.markdown(text, extensions=["tables", "sane_lists"])
    docs = repo / "docs"
    docs.mkdir(exist_ok=True)

    # No Jekyll: it would silently reinterpret the files, and there is nothing
    # here that needs it.
    (docs / ".nojekyll").write_text("")

    (docs / "privacy.html").write_text(PAGE.format(
        title="AerialPod — Privacy Policy",
        description=("How AerialPod handles your data: it does not collect any, "
                     "and you choose what it syncs with."),
        style=STYLE,
        back='<a class="back" href="./">← AerialPod</a>\n',
        body=body,
    ))

    (docs / "index.html").write_text(PAGE.format(
        title="AerialPod",
        description="A podcast player for Linux and Android that syncs directly "
                    "between your own devices.",
        style=STYLE,
        back="",
        body=(
            "<h1>AerialPod</h1>\n"
            "<p>A podcast player for Linux and Android. Your library syncs "
            "directly between your own devices over your local network — no "
            "account required, and no server in the middle.</p>\n"
            "<p><a href='./privacy.html'>Privacy policy</a><br>"
            "<a href='https://github.com/ghostlypi/aerialpod'>Source on GitHub</a></p>\n"
        ),
    ))

    for f in ("index.html", "privacy.html"):
        size = (docs / f).stat().st_size
        print(f"  docs/{f}  ({size:,} bytes)")
    print("\n  Enable at: Settings → Pages → Deploy from a branch → main → /docs")
    print("  Then live at: https://ghostlypi.github.io/aerialpod/privacy.html")
    return 0 if not hits else 2


if __name__ == "__main__":
    sys.exit(render(Path(sys.argv[1]), sys.argv[2] == "1"))
