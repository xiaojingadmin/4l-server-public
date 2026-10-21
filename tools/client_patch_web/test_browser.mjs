// Node-side harness for the same browser modules. Used by test_browser_patch.py.
import fs from 'node:fs/promises';
import {addAts, isHttpOrigin, patchMetadata} from './il2cpp.js';
import {
  findAppPlist,
  findMetadataEntry,
  openZip,
  readEntryBytes,
  writeZip,
} from './zip.js';

async function main() {
  const [sourcePath, outputPath, apiOrigin, assetOrigin, kindArg] = process.argv.slice(2);
  if (!sourcePath || !outputPath || !apiOrigin || !assetOrigin) {
    throw new Error('usage: node test_browser.mjs <source> <output> <api> <asset> [apk|ipa]');
  }
  const kind = kindArg || (sourcePath.toLowerCase().endsWith('.ipa') ? 'ipa' : 'apk');
  const source = new Uint8Array(await fs.readFile(sourcePath));
  const zip = await openZip(new Blob([source]));
  const metadataEntry = findMetadataEntry(zip, kind);
  const metadata = await readEntryBytes(zip, metadataEntry);
  const patched = patchMetadata(metadata, {apiOrigin, assetOrigin, kind});
  const replacements = new Map([[metadataEntry.name, patched.metadata]]);
  let ats = false;
  if (kind === 'ipa' && (isHttpOrigin(apiOrigin) || isHttpOrigin(assetOrigin))) {
    const plistEntry = findAppPlist(zip);
    const plist = await readEntryBytes(zip, plistEntry, 16 * 1024 * 1024);
    replacements.set(plistEntry.name, addAts(plist));
    ats = true;
  }
  const output = await writeZip(zip, replacements, {dropSignatures: kind === 'apk'});
  await fs.writeFile(outputPath, new Uint8Array(await output.arrayBuffer()));
  console.log(JSON.stringify({
    kind,
    metadata_entry: metadataEntry.name,
    api_patch: patched.apiPatch.length,
    asset_literal_count: patched.assetPatch.literal_count,
    asset_constant_count: patched.assetPatch.constant_count,
    ats,
    output_size: output.size,
  }));
}

main().catch((error) => {
  console.error(error && error.stack ? error.stack : String(error));
  process.exitCode = 1;
});
