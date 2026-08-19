#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const source = JSON.parse(await readFile(resolve(root, "warrior-spatial-diversity-second-pass-sprite-prompts.json"), "utf8"));
const original = source.skills.find((entry) => entry.catalogId === "warrior_t14_c04");
if (!original) throw new Error("warrior_t14_c04 missing from second-pass manifest");

const commonPrompt = "Use case: stylized-concept. Asset type: production 2D combat VFX overlay sprite sheet for AlarmQuest. This is a targeted third-pass correction from textual QA only; do not use rejected PNGs as image references. Create exactly 16 chronological frames in a strict 4-column by 4-row row-major sheet. Every cell is an independent 361:160 combat viewport; final sheet 1444:640; Detailed premium Korean fantasy action-RPG rendering; transparent RGBA; fixed camera; clean alpha; no pixel art. Keep CORNER_TO_CORNER_CRUST_WAVE_WIDE: the crust wave travels from lower-right toward upper-left and never originates at screen center. F01 mean alpha below 2% and occupancy below 8%. By F08, separated foreground, middle, and far crust layers must already span at least 96% width and 86% height but remain visibly subordinate to F09. F09 is the unique maximum: a near vertical crust fin cropped by right and bottom, a broad middle shelf exiting the left edge, a thin high fracture ribbon cropped by top-left, and sparse far debris touching upper-right. The combined F09 alpha bounding box must exceed 98% width and 95% height with transparent air channels between layers. F10 must show an unmistakable collapse to no more than 60% of F09 mean alpha and occupancy: remove the high ribbon and most airborne rocks immediately, break the middle shelf into cooling fragments, and retain only a dim sinking right fin. F11-F16 strictly lose energy with no rebuilding; F16 mean alpha below 1.5% and occupancy below 6%. Every frame must be separately clipped with no neighboring-cell band. Visual effect only; no character, enemy, scenery, UI, text, or fully rendered weapon.";

const negativePrompt = "pixel art, retro sprite, low resolution, rejected image reference, existing AlarmQuest VFX, character, enemy, weapon, scenery, UI, text, label, grid, border, gutter, opaque background, universal center anchor, center crater, central pillar, central burst, X, cross, radial flower, mirrored plates, symmetric wings, ring, dome, vortex, spiral, one giant stretched object, panorama smear, empty left edge at F08 or F09, empty top region at F08 or F09, F10 more than sixty percent of F09 energy, slow post-peak fade, post-peak reconstruction, late second peak, opaque horizontal band, rectangular alpha panel, adjacent frame residue, top image band, bottom image band, duplicated frame, missing frame, wrong order, camera motion, broad F01, broad F16, muddy colors, jagged alpha";

const output = {
  schemaVersion: 1,
  templateOnly: false,
  scope: "Warrior t14 targeted decay and F08 coverage revision",
  sourcePolicy: {
    existingVfxIgnored: true,
    rejectedSheetsAreNotImageReferences: true,
    qaInputs: ["catalogId", "skill name", "second-pass textual QA defects", "spatial grammar"],
  },
  gateway: source.gateway,
  settings: source.settings,
  commonPrompt,
  negativePrompt,
  skills: [{
    catalogId: original.catalogId,
    level: original.level,
    name: original.name,
    grammar: "CORNER_TO_CORNER_CRUST_WAVE_DECAY_LOCK",
    generationPrompt: "Third-pass correction for ‘지각 대폭쇄’. Preserve the successful off-center lower-right to upper-left crust wave and its separated depth layers. Correct only two defects: F08 occupied height was 71.88%, so introduce the top-left ribbon and upper-right far debris earlier to exceed 86% height; F10 retained 80.89% of F09 mean alpha, so make F10 brutally and visibly weaker at no more than 60% by removing the high layer and most rocks at once. Keep F09 at least 98% wide and 95% high, preserve transparent gaps, and never convert the wave into a central crater or opaque band.",
  }],
};

const outputPath = resolve(root, "warrior-t14-decay-revision-sprite-prompts.json");
await writeFile(outputPath, `${JSON.stringify(output, null, 2)}\n`, "utf8");
process.stdout.write(`1\t${outputPath}\n`);
