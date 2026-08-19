#!/usr/bin/env node

import { readFile, readdir } from "node:fs/promises";
import { basename, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));

const classBatches = new Map([
  ["ROGUE", ["rogue-needle-crown", "rogue-umbra-collapse", "rogue-venom-helix", "rogue-snare-convergence", "rogue-heartseal-execution"]],
  ["RANGER", ["ranger-horizon-pinpoint", "ranger-skyburst-volley", "ranger-tempest-string", "ranger-predator-domain", "ranger-lunar-constellation"]],
  ["MAGE", ["mage-solar-forge", "mage-cryostasis-cathedral", "mage-thunder-circuit", "mage-arcane-geometry", "mage-cosmic-orbital"]],
  ["CLERIC", ["cleric-dawn-revelation", "cleric-celestial-verdict", "cleric-exorcism-seal", "cleric-sacred-flame", "cleric-angelic-host"]],
  ["PALADIN", ["paladin-consecrated-rift", "paladin-aegis-citadel", "paladin-judgment-gravity", "paladin-aurora-vow", "paladin-sovereign-oath"]],
]);

const expected = {
  mode: "detailed",
  frameWidth: 361,
  frameHeight: 160,
  frameCount: 16,
  columns: 4,
  rows: 4,
  sheetWidth: 1444,
  sheetHeight: 640,
  fps: 16,
  loop: false,
};

const issues = [];
const allCatalogIds = [];
const summary = {};
const [appSource, stylesSource, indexSource] = await Promise.all([
  readFile(resolve(scriptDir, "app.js"), "utf8"),
  readFile(resolve(scriptDir, "styles.css"), "utf8"),
  readFile(resolve(scriptDir, "index.html"), "utf8"),
]);

if (/image-rendering\s*:\s*(?:pixelated|crisp-edges)/i.test(stylesSource)) {
  issues.push("styles.css: pixel-art image rendering is forbidden for Detailed sprites");
}
const spriteCss = stylesSource.match(/\.charge-sprite\s*\{[^}]*\}/s)?.[0] ?? "";
if (!/image-rendering\s*:\s*auto/i.test(spriteCss)) {
  issues.push("styles.css: .charge-sprite must use image-rendering: auto");
}
if (!indexSource.includes('id="chargeSprite"') || !indexSource.includes('width="361"') || !indexSource.includes('height="160"')) {
  issues.push("index.html: authored sprite canvas must be 361x160");
}
for (const contractLine of [
  "const AUTHORED_SPRITE_COLUMNS = 4;",
  "const AUTHORED_SPRITE_ROWS = 4;",
  "const AUTHORED_SPRITE_FRAME_MILLIS = 62.5;",
]) {
  if (!appSource.includes(contractLine)) issues.push(`app.js: missing renderer contract ${contractLine}`);
}

function assertEqual(actual, wanted, label) {
  if (actual !== wanted) issues.push(`${label}: expected ${JSON.stringify(wanted)}, received ${JSON.stringify(actual)}`);
}

function pngMetadata(bytes) {
  const signature = [137, 80, 78, 71, 13, 10, 26, 10];
  if (bytes.length < 26 || !signature.every((value, index) => bytes[index] === value)) {
    throw new Error("not a valid PNG");
  }
  return {
    width: bytes.readUInt32BE(16),
    height: bytes.readUInt32BE(20),
    bitDepth: bytes[24],
    colorType: bytes[25],
  };
}

for (const [heroClass, slugs] of classBatches) {
  const classSummary = { manifests: 0, jobs: 0, uniqueCatalogIds: 0, pngs: 0, validPngs: 0 };
  const classCatalogIds = [];
  for (const slug of slugs) {
    const promptPath = resolve(scriptDir, `${slug}-sprite-prompts.json`);
    const jobsPath = resolve(scriptDir, `${slug}-sprite-jobs.json`);
    const [promptManifest, jobsManifest] = await Promise.all([
      readFile(promptPath, "utf8").then(JSON.parse),
      readFile(jobsPath, "utf8").then(JSON.parse),
    ]);
    classSummary.manifests += 1;
    assertEqual(promptManifest.templateOnly, false, `${slug}.templateOnly`);
    assertEqual(promptManifest.sourcePolicy?.existingVfxIgnored, true, `${slug}.sourcePolicy.existingVfxIgnored`);
    for (const [key, value] of Object.entries(expected)) {
      if (key === "sheetWidth" || key === "sheetHeight") continue;
      assertEqual(promptManifest.settings?.[key], value, `${slug}.settings.${key}`);
      assertEqual(jobsManifest.expected?.[key], value, `${slug}.expected.${key}`);
    }
    assertEqual(jobsManifest.expected?.sheetWidth, expected.sheetWidth, `${slug}.expected.sheetWidth`);
    assertEqual(jobsManifest.expected?.sheetHeight, expected.sheetHeight, `${slug}.expected.sheetHeight`);
    assertEqual(promptManifest.skills?.length, 20, `${slug}.skills.length`);
    assertEqual(jobsManifest.jobs?.length, 20, `${slug}.jobs.length`);
    assertEqual(basename(jobsManifest.outputDir ?? ""), slug, `${slug}.outputDir`);
    if (!appSource.includes(`"${slug}"`)) issues.push(`${slug}: app.js asset map is missing the batch slug`);

    const promptIds = (promptManifest.skills ?? []).map((skill) => skill.catalogId);
    const jobIds = (jobsManifest.jobs ?? []).map((job) => job.catalogId);
    if (JSON.stringify(promptIds) !== JSON.stringify(jobIds)) issues.push(`${slug}: prompt/job catalogId order differs`);
    classCatalogIds.push(...promptIds);
    allCatalogIds.push(...promptIds);
    classSummary.jobs += jobIds.length;

    const outputDir = resolve(jobsManifest.outputDir);
    let entries = [];
    try {
      entries = await readdir(outputDir, { withFileTypes: true });
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    const pngNames = entries.filter((entry) => entry.isFile() && entry.name.endsWith(".png")).map((entry) => entry.name);
    classSummary.pngs += pngNames.length;
    for (const name of pngNames) {
      try {
        const metadata = pngMetadata(await readFile(resolve(outputDir, name)));
        if (metadata.width !== 1444 || metadata.height !== 640 || metadata.bitDepth !== 8 || metadata.colorType !== 6) {
          issues.push(`${slug}/${name}: invalid PNG contract ${JSON.stringify(metadata)}`);
        } else {
          classSummary.validPngs += 1;
        }
      } catch (error) {
        issues.push(`${slug}/${name}: ${error.message}`);
      }
    }
  }
  classSummary.uniqueCatalogIds = new Set(classCatalogIds).size;
  if (classSummary.uniqueCatalogIds !== 100) issues.push(`${heroClass}: expected 100 unique catalogIds, received ${classSummary.uniqueCatalogIds}`);
  summary[heroClass] = classSummary;
}

const uniqueTotal = new Set(allCatalogIds).size;
if (allCatalogIds.length !== 500 || uniqueTotal !== 500) {
  issues.push(`all classes: expected 500 total and unique catalogIds, received ${allCatalogIds.length} total and ${uniqueTotal} unique`);
}

const literalAssetOverrides = new Map();
for (const match of appSource.matchAll(/\["([a-z]+_t\d{2}_c\d{2})",\s*"(custom-assets\/[^"\n]+\.png)"\]/g)) {
  literalAssetOverrides.set(match[1], match[2]);
}
const readySummary = {};
for (const [heroClass, slugs] of classBatches) {
  if (heroClass === "WARRIOR") continue;
  const readyBody = appSource.match(new RegExp(`const ${heroClass}_READY_SPRITES = new Set\\(\\[([\\s\\S]*?)\\]\\);`))?.[1] ?? "";
  const readyIds = [...readyBody.matchAll(/"([a-z]+_t\d{2}_c\d{2})"/g)].map((match) => match[1]);
  let valid = 0;
  for (const catalogId of readyIds) {
    if (!allCatalogIds.includes(catalogId)) {
      issues.push(`${heroClass}: ready catalogId is not in the 500-skill program: ${catalogId}`);
      continue;
    }
    const candidate = Number.parseInt(catalogId.slice(-2), 10);
    const relativeAsset = literalAssetOverrides.get(catalogId)
      ?? `custom-assets/${slugs[candidate - 1]}/${catalogId}.png`;
    try {
      const metadata = pngMetadata(await readFile(resolve(scriptDir, relativeAsset.replace(/^custom-assets\//, "custom-assets/"))));
      if (metadata.width !== 1444 || metadata.height !== 640 || metadata.bitDepth !== 8 || metadata.colorType !== 6) {
        issues.push(`${heroClass}: ready sprite ${catalogId} has invalid PNG contract ${JSON.stringify(metadata)}`);
      } else {
        valid += 1;
      }
    } catch (error) {
      issues.push(`${heroClass}: ready sprite ${catalogId} asset is unavailable: ${error.message}`);
    }
  }
  readySummary[heroClass] = { ready: readyIds.length, valid };
}

process.stdout.write(`${JSON.stringify({ ok: issues.length === 0, totalCatalogIds: allCatalogIds.length, uniqueCatalogIds: uniqueTotal, summary, readySummary, issues }, null, 2)}\n`);
if (issues.length) process.exitCode = 1;
