#!/usr/bin/env bash
# Start the external console beside the dedicated test Gateway, never recreate it.
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose=(docker compose -f "$project_dir/docker/camel-hawtio.yml" -f "$project_dir/docker/camel-hawtio-console.yml")
if [[ -z "$("${compose[@]}" ps -q gateway)" ]]; then
  echo 'Start the dedicated Gateway first: bash scripts/camel-hawtio-smoke-test.sh' >&2
  exit 1
fi
"${compose[@]}" up -d --build --no-deps --wait hawtio-proxy hawtio
python3 - "${HAWTIO_PORT:-9390}" <<'PY'
import sys
import time
import urllib.error
import urllib.request
url = "http://127.0.0.1:%s/hawtio/" % sys.argv[1]
for attempt in range(60):
    try:
        with urllib.request.urlopen(url, timeout=3) as response:
            if response.status == 200:
                break
    except (OSError, urllib.error.URLError):
        pass
    if attempt == 59:
        raise SystemExit("Hawtio did not become ready; inspect Compose logs for hawtio")
    time.sleep(2)
PY
printf 'Hawtio: http://127.0.0.1:%s/hawtio/\n' "${HAWTIO_PORT:-9390}"
echo 'Remote connection: http://hawtio-proxy:9190/jolokia/ (username: hawtio)'
echo 'Development password defaults to password; override CAMEL_HAWTIO_PROXY_PASSWORD.'
