"""Renders admin boards to design/screens/admin/png with Edge headless.

The canvas tool's runtime is not in the repository, so this does its part: it fills the Sidebar
import for the active entry, drops the x-dc and helmet wrappers, and puts the dashed logo box where
the logo goes. Run from the repository root with the boards to render:

    python design/screens/admin/tools/render_boards.py PaymentProviders Jobs ...
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / "design/screens/admin/source"
PNG = ROOT / "design/screens/admin/png"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
BASE = "display: flex; align-items: center; gap: 10px; min-height: 38px; padding: 0 10px; border-radius: 10px; text-decoration: none; font-size: 14.5px; "
ON = BASE + "background: #E3EEEA; color: #143F37; font-weight: 700"
OFF = BASE + "background: transparent; color: #2C3833; font-weight: 500"
LOGO = ('<span style="display: inline-flex; align-items: center; justify-content: center; width: 96px; height: 32px; '
        'border: 1px dashed #7F8B84; border-radius: 6px; font-size: 12px; color: #4F5D57">logo</span>')


def body(text):
    inner = text.split("<x-dc>", 1)[1].split("</x-dc>", 1)[0]
    return re.sub(r"<helmet>.*?</helmet>", "", inner, flags=re.S)


def sidebar(name, active):
    inner = body((SOURCE / f"{name}.dc.html").read_text(encoding="utf-8"))
    inner = re.sub(r"\{\{s\.(\w+)\}\}", lambda m: ON if m.group(1) == active else OFF, inner)
    inner = re.sub(r"\{\{c\.(\w+)\}\}", lambda m: "page" if m.group(1) == active else "false", inner)
    return re.sub(r"<img [^>]*alt=\"Solace Care\"[^>]*>", LOGO, inner)


def render(name):
    text = (SOURCE / f"{name}.dc.html").read_text(encoding="utf-8")
    styles = "".join(re.findall(r"<style>.*?</style>", text, flags=re.S))
    lang = re.search(r'<html lang="(\w+)"', text).group(1)
    size = re.search(r'"\$preview":\{"width":(\d+),"height":(\d+)\}', text)
    main = re.sub(r'<dc-import name="(\w+)" active="(\w+)"[^>]*></dc-import>',
                  lambda m: sidebar(m.group(1), m.group(2)), body(text))
    page = f'<!doctype html><html lang="{lang}"><head><meta charset="utf-8">{styles}</head><body>{main}</body></html>'
    with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False, encoding="utf-8") as f:
        f.write(page)
    out = PNG / f"{name}.png"
    subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    f"--window-size={size.group(1)},{size.group(2)}", f"--screenshot={out}", Path(f.name).as_uri()],
                   check=True, capture_output=True)
    print("rendered", out.relative_to(ROOT))


if __name__ == "__main__":
    for board in sys.argv[1:]:
        render(board)
        render(board + "_fr")
