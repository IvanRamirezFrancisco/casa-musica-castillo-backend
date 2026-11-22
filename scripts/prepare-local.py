"""Read-only preparation of the authorized existing local database.

This command never creates databases/clusters, starts PostgreSQL, resets test data,
or performs migrations. Administrative migration requires separate explicit approval.
"""
from pathlib import Path
import argparse, shutil, subprocess as sp

parser = argparse.ArgumentParser(description=__doc__)
parser.parse_args()
root = Path(__file__).resolve().parent.parent
assert (root / '.local-dev/application.properties').is_file(), 'Restore the authorized private local configuration first'
shell = shutil.which('pwsh') or shutil.which('powershell')
assert shell, 'PowerShell is required for the read-only local preflight'
result = sp.run([shell, '-NoProfile', '-File', str(root / 'scripts/Invoke-LocalMigrations.ps1')], cwd=root)
raise SystemExit(result.returncode)
