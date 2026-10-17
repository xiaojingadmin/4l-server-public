# Bundled data provenance

This directory contains the runtime data copied from the local reference server so the
Spring Boot server can run from this repository alone. `H:\4l server` is a one-time
source and is not read at runtime.

## Copied files

| Target | Source | Notes |
| --- | --- | --- |
| `src/main/resources/client_defaults/` | `H:\4l server\build\client_defaults\` | 67 files after adding the item masters: 20 `base_res_content_*.json`, 20 `.source.json`, static master JSON, `master_tsv/` |
| `build/catalog/5.1.0-R2604100/` | `H:\4l server\build\catalog\5.1.0-R2604100\` | `catalog_entries.json` (28 MB), `verification.json`, `masterdata_samples/`, encoded blob |
| `build/catalog/6.0.0-R2606100/` | `H:\4l server\build\catalog\6.0.0-R2606100\` | `catalog_entries.json` (29 MB), same layout |
| `data/master_5.1.0.db` | `H:\4l server\22\master.db` | SQLite master; `items` has 939 rows × 12 columns |
| `data/cards.db` | `H:\4l server\22\cards.db` | SQLite card master |
| `data/master_items_5.1.0.json` | `data/master_5.1.0.db` | 939 unique item rows, 12 columns |
| `data/master_items_6.0.0.json` | `H:\4l server` 6.0.0 masterdata | 958 unique item rows, 12 columns |

Not copied: `private_server/users.db`, `private_server/users.json`, logs, captures,
sessions. The public repository contains no real account data.

## Regenerate the item JSON

The one-off exporter is `tools/export_master_items.py`:

```powershell
python tools/export_master_items.py --source-root "H:/4l server" `
  --base 5.1.0 --out src/main/resources/client_defaults/master_items_5.1.0.json
python tools/export_master_items.py --source-root "H:/4l server" `
  --base 6.0.0 --out src/main/resources/client_defaults/master_items_6.0.0.json
```

`master_items.json` is kept as the 5.1.0 compatibility name. The Java server picks
`master_items_<client-version>.json` first and falls back to `master_items.json`.

## Verify

`data/MANIFEST.sha256` records SHA-256 for every copied/exported file (85 entries).
Text files are normalized to LF (matching `.gitattributes`) before hashing, so the manifest
verifies in a fresh clone on Windows, macOS and Linux. From the repository root:

```bash
sha256sum -c data/MANIFEST.sha256
```
