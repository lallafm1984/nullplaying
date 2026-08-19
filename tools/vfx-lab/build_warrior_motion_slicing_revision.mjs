#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve("tools/vfx-lab");
const cleave = JSON.parse(await readFile(resolve(root, "warrior-cleave-sprite-prompts.json"), "utf8"));
const crush = JSON.parse(await readFile(resolve(root, "warrior-crush-sprite-prompts.json"), "utf8"));
const charge = JSON.parse(await readFile(resolve(root, "charge-sprite-revision-prompts.json"), "utf8"));

const targets = [
  [cleave, "warrior_t04_c01", "CELL_BOUNDARY_LOCK"],
  [cleave, "warrior_t05_c01", "CELL_BOUNDARY_LOCK"],
  [cleave, "warrior_t07_c01", "CELL_BOUNDARY_LOCK"],
  [cleave, "warrior_t12_c01", "CELL_BOUNDARY_LOCK"],
  [crush, "warrior_t06_c02", "CENTER_AXIS_LOCK"],
  [charge, "warrior_t03_c03", "CENTER_AXIS_LOCK"],
  [charge, "warrior_t13_c03", "CENTER_AXIS_LOCK"],
];

const cellBoundaryRule = [
  "CELL_BOUNDARY_LOCK correction: treat each 361x160 cell as an isolated final viewport and never compose artwork as if the four sheet rows were one continuous picture.",
  "No fragment, glow, ground strip, shadow, slash trail, or continuation from another chronological frame may appear along the top or bottom edge.",
  "Keep a clean transparent 6-pixel safety band at the top and bottom in F01-F07 and F11-F16; in F08-F10 only the coherent main impact may intentionally crop the viewport edge, never a detached horizontal strip.",
  "Maintain one consistent ground line and remove any secondary band that resembles the next or previous sprite-sheet row.",
].join(" ");

const centerAxisRule = [
  "CENTER_AXIS_LOCK correction: the structural impact spine, vanishing point, and brightest core must remain locked at x=50% in every frame, with the brightest core centroid kept within x=48%-52% from F04-F14.",
  "Do not curve, bow, orbit, drift, or step the central axis left or right; the camera and target point are immovable.",
  "Asymmetric debris may vary outside the core, but it must never pull the principal pressure shaft, penetration needle, or contact point away from center.",
].join(" ");

const commonPrompt = [
  "Use case: stylized-concept. Asset type: production 2D combat VFX overlay sprite sheet for AlarmQuest.",
  "Regenerate the listed defective skill from scratch without using its existing sheet as a visual reference.",
  "Create exactly 16 chronological frames in a strict 4-column by 4-row, row-major sheet; each cell is exactly 361x160 and the final sheet is 1444x640 RGBA.",
  "Detailed premium Korean fantasy action-RPG VFX, not pixel art. Use a fixed camera, fixed scale, fixed ground line, and fixed central target at x=50%, y=55%.",
  "Maximum center impact density and natural full-screen coverage take priority; damage-number visibility does not matter.",
  "Every cell must be independently readable with no visual contamination from an adjacent cell, while the animation remains temporally continuous.",
  "F01 is nearly transparent, F02-F04 gather, F05-F07 accelerate, F08 is main contact, F09 is peak, F10-F12 release through the viewer, F13-F14 retain a readable afterimage, and F15-F16 fade cleanly.",
  "No camera pan, zoom, roll, lateral target motion, loop reset, grid, labels, captions, borders, or opaque background. Visual effect only; no character or enemy.",
].join(" ");

const negativePrompt = [
  cleave.negativePrompt,
  "adjacent frame residue, next-frame image, previous-frame image, cross-cell continuation, row-spanning artwork, bottom strip, bottom image band, detached ground band, unrelated horizontal edge artifact, content leaking across cell boundary, mismatched ground line",
  "moving center point, drifting vanishing point, lateral core drift, curved central spine, bowed penetration axis, wandering vertical shaft, alternating left-right impact, off-center brightest core, camera shake, camera jitter",
].join(", ");

const skills = targets.map(([manifest, catalogId, correction]) => {
  const skill = manifest.skills.find((candidate) => candidate.catalogId === catalogId);
  if (!skill) throw new Error(`Missing source prompt for ${catalogId}`);
  const correctionPrompt = correction === "CELL_BOUNDARY_LOCK" ? cellBoundaryRule : centerAxisRule;
  return {
    catalogId: skill.catalogId,
    level: skill.level,
    name: skill.name,
    correction,
    generationPrompt: `${skill.generationPrompt} ${correctionPrompt}`,
  };
});

const manifest = {
  schemaVersion: 1,
  scope: "Warrior QA correction for frame-boundary contamination and center-axis drift",
  gateway: cleave.gateway,
  settings: {
    ...cleave.settings,
    useReference: false,
  },
  commonPrompt,
  negativePrompt,
  skills,
};

const outputPath = resolve(root, "warrior-motion-slicing-revision-prompts.json");
await writeFile(outputPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
process.stdout.write(`${outputPath}\n`);
