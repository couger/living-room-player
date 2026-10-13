"""Export independent local system diagnostics; never uploads to a server.

The live log files can change during export. This is an evidence collection,
not a transactional snapshot. The result may contain private system log data.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--device', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--legacy', action='store_true', help='Export preserved pre-split history and soak data instead')
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    name = ('legacy-diagnostics-' if args.legacy else 'system-diagnostics-') + datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '.tar.gz'
    stage = '/data/local/tmp/system-diagnostics-export-' + uuid.uuid4().hex + '.tar.gz'
    select = ('base=/data/adb/living-player; folder=diagnostics; ' if args.legacy else
              'if [ -d /data/adb/system-diagnostics/logs/current ]; then base=/data/adb/system-diagnostics; folder=logs; else base=/data/adb/living-player; folder=diagnostics; fi; ')
    cmd = (select + 'test -d "$base/$folder/current" || exit 2; '
           'umask 077; tar -c -f - -C "$base" "$folder" 2>/dev/null | gzip -1 -c > ' + stage + '; '
           'chown 2000:2000 ' + stage + '; chmod 600 ' + stage)

    def root(command):
        result = subprocess.run([args.adb, '-s', args.device, 'shell', "su -c '" + command + "'"],
                                capture_output=True, timeout=120)
        if result.returncode:
            raise RuntimeError('Diagnostic export failed; check root access and whether recording is enabled')

    try:
        root(cmd)
        subprocess.run([args.adb, '-s', args.device, 'pull', stage, str(output / name)], check=True,
                       capture_output=True, timeout=120)
        with tarfile.open(output / name) as archive:
            entries = archive.getmembers()
            # Read every regular member to also validate gzip/transfer integrity.
            for entry in entries:
                if entry.isfile():
                    with archive.extractfile(entry) as stream:
                        while stream.read(1024 * 1024):
                            pass
        digest = hashlib.sha256((output / name).read_bytes()).hexdigest()
        (output / (name + '.json')).write_text(json.dumps({'archive': name, 'sha256': digest,
                                                         'members': len(entries), 'private': True}, indent=2), encoding='utf-8')
        print('Local private archive saved:', output / name)
    finally:
        root('rm -f ' + stage)


if __name__ == '__main__':
    main()
