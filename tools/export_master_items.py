#!/usr/bin/env python3
"""One-off exporter for the Java server's static item master.

This tool reads the authoritative data from a local 4l-server checkout and writes
the JSON fallback used when MariaDB's ``master_items`` is unavailable. It is not
used by the running Java server.

Examples:
  python tools/export_master_items.py --source-root "H:/4l server" \
      --base 5.1.0 --out src/main/resources/client_defaults/master_items_5.1.0.json
  python tools/export_master_items.py --source-root "H:/4l server" \
      --base 6.0.0 --out src/main/resources/client_defaults/master_items_6.0.0.json
"""
import argparse
import json
import os
import sqlite3
import sys
from pathlib import Path

ITEM_FIELDS = (
    'Id Name NameFurigana ItemType ItemCategory Rarity EffectValue '
    'LimitNum RequestableNum Description StartTime EndTime'
).split()


def from_sqlite(path):
    connection = sqlite3.connect('file:%s?mode=ro' % Path(path).as_posix(), uri=True)
    connection.row_factory = sqlite3.Row
    try:
        rows = [dict(row) for row in connection.execute(
            'SELECT %s FROM items ORDER BY Id' % ', '.join(ITEM_FIELDS))]
    finally:
        connection.close()
    return rows


def from_masterdata(source_root, base):
    os.environ['SIF2_MASTER_BASE'] = base
    for path in (source_root / 'private_server', source_root, source_root / 'admin'):
        if str(path) not in sys.path:
            sys.path.insert(0, str(path))
    import custom_content
    import master_table

    table = master_table.Table.parse(custom_content.master_plain('items.tsv'))
    return [dict(zip(ITEM_FIELDS, values)) for values in zip(*table.columns)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-root', required=True, type=Path,
                        help='local 4l-server checkout (data source only)')
    parser.add_argument('--base', required=True, choices=('5.1.0', '6.0.0'))
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()

    sqlite = args.source_root / '22' / 'master.db'
    if args.base == '5.1.0' and sqlite.is_file():
        rows = from_sqlite(sqlite)
        source = sqlite
    else:
        rows = from_masterdata(args.source_root, args.base)
        source = 'masterdata %s' % args.base

    ids = {row['Id'] for row in rows}
    if len(ids) != len(rows):
        raise SystemExit('duplicate item Ids: %d rows, %d unique' % (len(rows), len(ids)))
    if not rows:
        raise SystemExit('no items exported from %s' % source)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open('w', encoding='utf-8') as handle:
        json.dump(rows, handle, ensure_ascii=False, indent=1)
        handle.write('\n')
    print('%s: %d items -> %s' % (source, len(rows), args.out))


if __name__ == '__main__':
    main()
