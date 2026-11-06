#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Refuse to overwrite existing configuration; generated credentials are never printed.
python3 - <<'PY'
import os,secrets
from pathlib import Path
content=Path('.env.example').read_text()
content=content.replace('DB_PASSWORD=\n','DB_PASSWORD='+secrets.token_hex(24)+'\n')
content=content.replace('SCHEDULER_API_KEY=\n','SCHEDULER_API_KEY='+secrets.token_hex(32)+'\n')
fd=os.open('.env',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as output:output.write(content)
print('Created .env with random local credentials (mode 600). Adjust DB_PORT/DB_URL if needed.')
PY
