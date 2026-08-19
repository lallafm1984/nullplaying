#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const source = JSON.parse(await readFile(resolve(root, "warrior-spatial-diversity-second-pass-sprite-prompts.json"), "utf8"));
const original = source.skills.find((entry) => entry.catalogId === "warrior_t17_c05");
if (!original) throw new Error("warrior_t17_c05 missing from second-pass manifest");

const commonPrompt = "Use case: stylized-concept. Asset type: production 2D combat VFX overlay sprite sheet for AlarmQuest. This is a targeted third-pass correction from textual QA only; do not use rejected PNGs as references. Create exactly 16 chronological frames in a strict 4-column by 4-row row-major sheet. Every cell is an independent 361:160 combat viewport; final sheet 1444:640; Detailed premium Korean fantasy action-RPG rendering; transparent RGBA; fixed camera; clean alpha; no pixel art. Keep NOMADIC_NINE_HIT_ROUTE: hits 1-8 move through different off-center locations, then hit 9 ends at x=82%, y=34%. F01 mean alpha below 2% and occupancy below 8%. F08 is a subordinate isolated strike at the far-right edge. F09 is the unique maximum but must not contain any pair of long lines that intersect, overlap, or point to a shared center. Make the ninth hit a single dominant, thick, short hooked cleave cropped by top and right around x=82%, y=34%. Fill the rest of the viewport only with four disconnected, short residual fragments cropped separately at left-middle, top-left, bottom-left, and bottom-right. Each residual fragment occupies a different depth, angle, length, and edge; every fragment stops before reaching the central 22% of the frame. Preserve broad transparent corridors between all five groups. The combined F09 bounding box exceeds 98% width and 94% height through edge distribution, never through crossing lines or enlargement. F10 must be at least 40% weaker; only the right-side ninth hook remains prominent while all four old edge fragments vanish. F11-F16 strictly decay with no reconstruction; F16 mean alpha below 1.5% and occupancy below 6%. Every frame is separately clipped with no neighboring-cell band. Visual effect only; no character, enemy, scenery, UI, text, or fully rendered weapon.";

const negativePrompt = "pixel art, retro sprite, low resolution, rejected image reference, existing AlarmQuest VFX, character, enemy, weapon, scenery, UI, text, label, grid, border, gutter, opaque background, universal center anchor, central contact, central starburst, center explosion, X, cross, intersecting lines, crossing slashes, converging diagonals, shared vanishing point, radial flower, flurry flower, mirrored wings, butterfly, ring, dome, vortex, spiral, repeated identical path, one giant stretched slash, panorama smear, empty left edge at F09, empty top or bottom edge at F09, opaque full-frame paint, F10 more than sixty percent of F09 energy, late second peak, post-peak reconstruction, adjacent frame residue, top image band, bottom image band, duplicated frame, missing frame, wrong order, camera motion, broad F01, broad F16, muddy colors, jagged alpha";

const output = {
  schemaVersion: 1,
  templateOnly: false,
  scope: "Warrior t17 targeted non-intersecting nine-hit route revision",
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
    grammar: "NOMADIC_NINE_HIT_ROUTE_NO_CROSS",
    generationPrompt: "Third-pass correction for ‘전쟁왕 광란’. The rejected F09 formed a huge central X from multiple long crossing streaks. Preserve the roaming hit order, but at F09 render only one dominant short hooked ninth cleave at the upper-right endpoint. Scatter four older wakes as disconnected short edge crops that never enter the central 22% and never touch or aim at one another. Edge distribution, rocks, sparks, and depth—not long diagonals—must create the full-screen bbox. F10 retains only a much dimmer upper-right hook and no old wakes.",
  }],
};

const outputPath = resolve(root, "warrior-t17-route-revision-sprite-prompts.json");
await writeFile(outputPath, `${JSON.stringify(output, null, 2)}\n`, "utf8");
process.stdout.write(`1\t${outputPath}\n`);
