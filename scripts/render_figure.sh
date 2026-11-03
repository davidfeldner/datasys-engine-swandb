#!/usr/bin/env bash
# Render the SwanDB architecture figure (docs/figures/deepseek.mmd) to PNG + SVG
# and dump every node's coordinates, so the caption's "top-to-bottom" claims can
# be checked against the real layout instead of being eyeballed.
#
# Usage:  ./scripts/render_figure.sh [input.mmd] [outdir]
set -euo pipefail

MMD="${1:-docs/figures/deepseek.mmd}"
OUT="${2:-docs/figures}"
mkdir -p "$OUT"

# --- caches ---------------------------------------------------------------
# A root-owned ~/.npm/_cacache breaks `npx` with EPERM, so keep npm's cache and
# puppeteer's browser download out of $HOME.
export npm_config_cache="${npm_config_cache:-/tmp/npmcache}"
export PUPPETEER_CACHE_DIR="${PUPPETEER_CACHE_DIR:-/tmp/pupcache}"

# --- 1. make sure a headless browser exists -------------------------------
# mermaid-cli drives Chrome via puppeteer; without this it dies with
# "Could not find chrome-headless-shell".
if ! ls "$PUPPETEER_CACHE_DIR"/chrome-headless-shell/*/*/chrome-headless-shell >/dev/null 2>&1; then
  echo "==> installing chrome-headless-shell into $PUPPETEER_CACHE_DIR"
  npx -y puppeteer@latest browsers install chrome-headless-shell
fi

# --- 2. chrome flags ------------------------------------------------------
# macOS needs --no-sandbox for headless-shell; without it the connection
# closes mid-render ("ConnectionClosedError: Connection closed").
FLAGS="$OUT/puppeteer-config.json"
cat > "$FLAGS" <<'JSON'
{"args":["--no-sandbox","--disable-gpu","--disable-dev-shm-usage","--single-process"]}
JSON

# --- 3. render ------------------------------------------------------------
echo "==> rendering $MMD"
npx -y @mermaid-js/mermaid-cli@11 \
  -p "$FLAGS" \
  -i "$MMD" \
  -o "$OUT/$(basename "${MMD%.mmd}").png" \
  -w 2000 -b white

npx -y @mermaid-js/mermaid-cli@11 \
  -p "$FLAGS" \
  -i "$MMD" \
  -o "$OUT/$(basename "${MMD%.mmd}").svg" \
  -b white

# --- 4. report the real geometry -----------------------------------------
# mermaid lays nodes out with Dagre; the y coordinate IS the vertical rank.
# Printing them sorted by y turns "is Engine at the top?" into a fact.
echo
echo "==> node positions (sorted top-to-bottom)"
python3 - "$OUT/$(basename "${MMD%.mmd}").svg" <<'PY'
import re, sys, xml.etree.ElementTree as ET
SVG = "http://www.w3.org/2000/svg"
root = ET.parse(sys.argv[1]).getroot()
rows = []
for g in root.iter(f"{{{SVG}}}g"):
    classes = (g.get("class") or "").split()
    if "node" not in classes and "cluster" not in classes:
        continue
    m = re.match(r"translate\(([-\d.]+)[,\s]+([-\d.]+)\)", g.get("transform") or "")
    if not m:
        continue
    x, y = float(m.group(1)), float(m.group(2))
    text = " ".join("".join(e.itertext()) for e in g.iter(f"{{{SVG}}}text")).strip()
    rows.append(("cluster" if "cluster" in classes else "node", x, y, text[:44]))
for kind, x, y, text in sorted(rows, key=lambda r: (r[2], r[1])):
    print(f"  {kind:8} y={y:8.1f} x={x:8.1f}  {text}")
PY
echo
echo "==> wrote $OUT/$(basename "${MMD%.mmd}").png and .svg"
