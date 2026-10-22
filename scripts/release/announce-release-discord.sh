#!/usr/bin/env bash
#
# Announce a release on the Discord #news channel: a digest of the version's
# CHANGELOG.md section (one line per entry, its bold lead) between the repo link
# and a link to the full section at the release tag.
#
# A digest, not the section: 0.10.0's section was 70 KB, about 38 messages, and
# Discord rate-limited the webhook partway through, leaving a cut-off post in
# #news. The digest fits in at most MAX_MESSAGES messages (the entry list is cut
# with "...and N more" past that), and a 429 is waited out and retried.
#
# Called by release.yml's announce job right after the GitHub release. Also safe
# to run standalone to (re)announce a version:
#   ./scripts/release/announce-release-discord.sh 0.3.6
#   ANNOUNCE_POST=0 ./scripts/release/announce-release-discord.sh 0.3.6   # print, post nothing
#
# Webhook: QOD_DISCORD_WEBHOOK_URL env var, falling back to a
# QOD_DISCORD_WEBHOOK_URL=... line in the untracked .env. The URL is a
# credential (anyone holding it can post to the channel) - never commit it.

set -euo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

version="${1:-}"
[[ -n "$version" ]] || { echo "usage: $0 <version>   (e.g. 0.3.6)" >&2; exit 1; }
version="${version#v}"

post="${ANNOUNCE_POST:-1}"
webhook="${QOD_DISCORD_WEBHOOK_URL:-}"
if [[ -z "$webhook" && -f "$REPO_DIR/.env" ]]; then
  webhook="$(sed -n 's/^QOD_DISCORD_WEBHOOK_URL=//p' "$REPO_DIR/.env" | tail -1)"
fi
if [[ "$post" == "1" && -z "$webhook" ]]; then
  echo "ERROR: QOD_DISCORD_WEBHOOK_URL not set (env var or .env line)." >&2
  exit 1
fi
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 not on PATH." >&2; exit 1; }

# The webhook travels by environment, never argv (visible in ps).
ANNOUNCE_WEBHOOK="$webhook" python3 - "$version" "$REPO_DIR/CHANGELOG.md" "$post" <<'PY'
import json, os, re, sys, time, urllib.error, urllib.request

version, changelog, post = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
LIMIT = 1900        # Discord caps a message at 2000 characters
MAX_MESSAGES = 3
REPO = "https://github.com/starlake-ai/quack-on-demand"

# The section body between "## <version>" and the next "## " heading.
section, found = [], False
for line in open(changelog, encoding="utf-8").read().splitlines():
    if line == f"## {version}":
        found = True
        continue
    if found and line.startswith("## "):
        break
    if found:
        section.append(line)

# One title per top-level entry: its bold lead, which may wrap over lines.
entries, current = [], None
for line in section:
    if line.startswith("- "):
        current = [line[2:]]
        entries.append(current)
    elif current is not None and line.startswith("  ") and line.strip():
        current.append(line.strip())
    else:
        current = None
titles = []
for entry in entries:
    m = re.match(r"\*\*(.+?)\*\*", " ".join(entry))
    if m:
        titles.append("- " + m.group(1).rstrip(".:,;"))
if not titles:
    print(f"WARNING: no entries under '## {version}' in CHANGELOG.md; announcing links only.",
          file=sys.stderr)

# GitHub's heading anchor: lowercase, punctuation dropped, spaces to hyphens.
anchor = re.sub(r"[^a-z0-9 -]", "", version.lower()).replace(" ", "-")
header = f"**quack-on-demand v{version} released**\n{REPO}"
footer = f"Full changelog: {REPO}/blob/v{version}/CHANGELOG.md#{anchor}"

def pack(lines):
    msgs, chunk = [], header
    for line in lines:
        if len(chunk) + len(line) + 1 > LIMIT:
            msgs.append(chunk)
            chunk = line
        else:
            chunk += "\n" + line
    if len(chunk) + len(footer) + 2 > LIMIT:
        msgs.append(chunk)
        chunk = footer
    else:
        chunk += "\n\n" + footer
    msgs.append(chunk)
    return msgs

shown = titles
messages = pack(shown)
while len(messages) > MAX_MESSAGES and shown:
    shown = shown[:-1]
    messages = pack(shown + [f"...and {len(titles) - len(shown)} more"])

def send(content):
    body = json.dumps({"content": content}).encode()
    for attempt in range(6):
        req = urllib.request.Request(
            os.environ["ANNOUNCE_WEBHOOK"] + "?wait=true", data=body,
            headers={"Content-Type": "application/json", "User-Agent": "qod-release-announce"})
        try:
            urllib.request.urlopen(req, timeout=30).read()
            return
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 5:
                raise SystemExit(f"ERROR: Discord answered HTTP {e.code}")
            try:
                wait = float(json.loads(e.read() or b"{}").get("retry_after", 2))
            except ValueError:
                wait = 2.0
            time.sleep(min(wait, 60) + 0.5)

for m in messages:
    if post:
        send(m)
    else:
        print(m + "\n---")
print(f"{'announced' if post else 'would announce'} v{version} on Discord #news "
      f"({len(messages)} message(s), {len(shown)}/{len(titles)} entries).", file=sys.stderr)
PY
