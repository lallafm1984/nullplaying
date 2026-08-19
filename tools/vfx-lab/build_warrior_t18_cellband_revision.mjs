#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const source = JSON.parse(await readFile(resolve(root, "warrior-t18-decay-revision-sprite-prompts.json"), "utf8"));
const original = source.skills.find((entry) => entry.catalogId === "warrior_t18_c04");
if (!original) throw new Error("warrior_t18_c04 missing from third-pass manifest");

const commonPrompt = "Use case: stylized-concept. Asset type: production 2D combat VFX overlay sprite sheet for AlarmQuest. This is a targeted fourth-pass correction from textual QA only; do not use rejected PNGs as references. Create exactly 16 chronological frames in a strict 4-column by 4-row row-major sheet. Every cell is an independent 361:160 combat viewport; final sheet 1444:640; Detailed premium Korean fantasy action-RPG rendering; transparent RGBA; fixed camera; clean alpha; no pixel art. Preserve RIDGELINE_AVALANCHE_DECAY_LOCK: a natural upper-right ridge collapses diagonally toward the lower-left through unequal near, middle, and far snow-rock curtains. It never originates at screen center. F01 mean alpha below 2% and occupancy below 8%. F08 is subordinate to F09. F09 is the unique maximum; its combined bbox reaches all four crop regions, but total alpha occupancy must stay between 68% and 82% by leaving three broad irregular transparent canyons between avalanche curtains. Do not paint a continuous white sheet. F10 must be no more than 55% of F09 mean alpha and 62% of F09 occupancy. F11-F16 strictly lose alpha and area without reconstruction. Remove whole curtains in sequence. F13-F16 must have a completely clean top 8-pixel strip with zero alpha and no horizontal line; all late debris must sit below y=12% and remain as separated particles. F14 mean alpha below 1.2% and occupancy below 7%; F15 below 0.25% and 1.5%; F16 below 0.1% and 0.5%. Every frame is separately clipped within its own 361×160 cell. Visual effect only; no character, enemy, scenery, UI, text, or fully rendered weapon.";

const negativePrompt = "pixel art, retro sprite, low resolution, rejected image reference, existing AlarmQuest VFX, character, enemy, weapon, scenery, UI, text, label, grid, border, gutter, opaque background, universal center anchor, central explosion, central mountain flower, mirrored ridges, symmetric wings, ring, dome, vortex, spiral, one giant stretched slab, panorama smear, opaque full-frame snow paint, F09 alpha occupancy above eighty-two percent, continuous white sheet, missing transparent canyons, slow post-peak fade, F10 above fifty-five percent of F09 mean alpha, persistent diagonal band, any horizontal line at top of F13, F14, F15, or F16, top-edge alpha in late frames, neighboring-cell image strip, top image band, bottom image band, adjacent frame residue, late second peak, post-peak reconstruction, duplicated frame, missing frame, wrong order, camera motion, broad F01, muddy colors, jagged alpha";

const output = {
  schemaVersion: 1,
  templateOnly: false,
  scope: "Warrior t18 targeted late-cell-band and peak-transparency revision",
  sourcePolicy: {
    existingVfxIgnored: true,
    rejectedSheetsAreNotImageReferences: true,
    qaInputs: ["catalogId", "skill name", "third-pass textual QA defects", "spatial grammar"],
  },
  gateway: source.gateway,
  settings: source.settings,
  commonPrompt,
  negativePrompt,
  skills: [{
    catalogId: original.catalogId,
    level: original.level,
    name: original.name,
    grammar: "RIDGELINE_AVALANCHE_CELL_SAFE",
    generationPrompt: "Fourth-pass correction for ‘만산 대붕괴’. Preserve the successful upper-right-to-lower-left avalanche path and the corrected sharp F10 decay. The rejected third pass leaked a thin horizontal neighboring-cell image strip along the top edge of F13 and F15, and F09 became 97.01% alpha-occupied white paint. Render every cell independently: F13-F16 top eight pixels must be exactly transparent with no line or streak. Keep F09 edge-to-edge through three separated depth curtains, but reduce alpha occupancy to 68-82% with broad transparent diagonal canyons so the detailed rocks and snow remain readable. Keep F10 below 55% mean energy, F14 sparse, F15 nearly empty, and F16 effectively transparent.",
  }],
};

const outputPath = resolve(root, "warrior-t18-cellband-revision-sprite-prompts.json");
await writeFile(outputPath, `${JSON.stringify(output, null, 2)}\n`, "utf8");
process.stdout.write(`1\t${outputPath}\n`);
