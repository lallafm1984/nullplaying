#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const sourcePath = resolve("tools/vfx-lab/warrior-crush-sprite-prompts.json");
const outputPath = resolve("tools/vfx-lab/warrior-crush-coverage-revision-prompts.json");
const source = JSON.parse(await readFile(sourcePath, "utf8"));
const original = source.skills.find(({ catalogId }) => catalogId === "warrior_t01_c02");

if (!original) throw new Error("warrior_t01_c02 is missing from the crush manifest.");

const baseCommonPrompt = original.commonPrompt ?? source.commonPrompt;
if (!baseCommonPrompt) throw new Error("The crush manifest is missing its shared commonPrompt.");

const commonPrompt = `${baseCommonPrompt} COVERAGE CORRECTION FOR THIS REVISION: the overhead mass is only the anticipation layer, never the whole composition. At F08-F10, add two different massive lateral compression walls and two foreground shock shelves that enter from beyond the left and right edges. Their combined alpha silhouette must touch or crop through both horizontal edges and fill at least 92% of the viewport width while the vertical impact remains centered. Keep the lateral structures naturally proportioned and depth-separated; do not stretch, mirror, or scale one object.`;
const generationPrompt = `${original.generationPrompt} Revision requirement: replace the narrow isolated central crater with a broad battlefield-spanning impact. At peak contact, left and right pressure walls, asymmetric foreground slabs, and an edge-to-edge ground shock crown must surround the central vertical column. The visible effect must reach and crop through both side edges while preserving the full-height descent.`;

const manifest = {
  ...source,
  batchName: "warrior-crush-coverage-revision",
  sourcePolicy: {
    ...(source.sourcePolicy ?? {}),
    revisionReason: "warrior_t01_c02 failed web combat viewport horizontal coverage at 490ms",
  },
  skills: [{ ...original, commonPrompt, generationPrompt }],
};

await writeFile(outputPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
process.stdout.write(`${outputPath}\n`);
