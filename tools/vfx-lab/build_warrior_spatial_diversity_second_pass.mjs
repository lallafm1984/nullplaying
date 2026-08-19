#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const source = JSON.parse(await readFile(resolve(root, "warrior-spatial-diversity-revision-sprite-prompts.json"), "utf8"));

const corrections = new Map([
  ["warrior_t01_c05", {
    grammar: "ROUGH_ZIGZAG_CASCADE_WIDE",
    prompt: "Second-pass correction for ‘거친 연속참’. Keep three separate rough cuts and never intersect them into an X. At F09 show three unequal, open zigzag lanes at different depths: a thin upper lane cropped by the left and top edges, a broad main lane starting outside the upper-right edge and exiting below x=28%, and a shorter lower foreground lane cropped by the bottom-right edge. Their contact notches sit at x=23%, x=71%, and x=84%; none sits at screen center. The combined three lanes must reach beyond both horizontal edges and at least 92% of height while transparent wedges remain between them. Do not enlarge one slash. F10 is at least 35% weaker and only fragments continue along the three existing lanes.",
    negative: "X-shaped crossed slashes, central slash flower, shared central contact, one enlarged crescent, parallel copy-pasted blades",
  }],
  ["warrior_t05_c04", {
    grammar: "LOWER_LEFT_SHEAR_LIFT_WIDE",
    prompt: "Second-pass correction for ‘지반 내려찍기’. Preserve the lower-left impact at x=24%, y=84%. F09 must fill the wide viewport with four independent depth layers: a near left plate cropped by left and bottom, a tall middle-left slab cropped by top, a long low fault shelf reaching completely through the right edge, and sparse far dust touching the upper-right crop. Keep large transparent air gaps and different scales. The combined bbox must exceed 96% width and 92% height; no single plate may exceed 52% width. The right side is a traveling shear consequence, not a second impact. F10 is at least 35% weaker and all plates continue rightward or settle without rebuilding.",
    negative: "center crater, central rock crown, one giant rock, isolated lower-left cluster, empty right third, circular ground explosion",
  }],
  ["warrior_t08_c04", {
    grammar: "DIAGONAL_FAULT_RUN_EDGE_TO_EDGE",
    prompt: "Second-pass correction for ‘지축 파쇄타’. Preserve the lower-left to upper-right traveling fault, but make the fracture genuinely edge-to-edge. At F09 one narrow luminous fault begins outside x=-4%, y=94% and exits outside x=104%, y=8%. Place a huge near basalt tooth cropped by the lower-left corner, three medium staggered teeth across x=38%-72%, and fine upper-right fragments cropped by top and right. The combined bbox must exceed 98% width and 94% height while the diagonal remains open and non-radial. The brightest segment stays near x=78%, y=35%. F10 is at least 35% weaker and cooling travels back along the same diagonal.",
    negative: "short centered diagonal, empty left edge, empty right edge, central star, crater, radial rock fan, thick rectangular band",
  }],
  ["warrior_t10_c01", {
    grammar: "OFF_AXIS_WALL_SHEAR_WIDE",
    prompt: "Second-pass correction for ‘성벽 양단’. Preserve the off-axis fortress shear. At F09 a massive near steel-stone plane is cropped by the entire left edge and top-left corner, while a thinner far buttress is cropped by the right edge and lower-right corner. Separate them with one irregular diagonal transparent cut corridor whose contact apex sits near x=64%, y=63%. Add only small middle-depth chips around that corridor. Combined material must exceed 97% width and 92% height, but the transparent corridor prevents an opaque panel. The two planes have different angles, thicknesses, textures, and motions and never mirror. F10 is at least 35% weaker as both planes continue outward.",
    negative: "central butterfly, mirrored wall halves, isolated left wall, empty right half, X, cross, circular lens, opaque full-frame slab",
  }],
  ["warrior_t14_c04", {
    grammar: "CORNER_TO_CORNER_CRUST_WAVE_WIDE",
    prompt: "Second-pass correction for ‘지각 대폭쇄’. Preserve the crust wave moving from lower-right toward the left. At F09 use four separated depths: a near vertical crust fin cropped by right and bottom, a broad middle shelf crossing the lower middle and exiting left, a high thin fracture ribbon cropped by the top-left edge, and sparse far fragments touching the upper-right edge. Combined bbox must exceed 97% width and 94% height with transparent air between slabs. No shared radial origin; the right fin collapses while the middle shelf travels left. F10 is at least 35% weaker and all structures continue in their established directions.",
    negative: "center crater, isolated right cluster, empty left third, rock flower, central pillar, mirrored plates, opaque horizontal band",
  }],
  ["warrior_t16_c02", {
    grammar: "ASYMMETRIC_OVERLORD_SMASH_WIDE",
    prompt: "Second-pass correction for ‘패왕 대강타’. Replace the rejected feather-wing peak with non-biological black-steel compression masses. At F09 the off-center contact remains x=74%, y=72%: one huge angular near wedge is cropped by right and bottom, one broad but thin gold pressure sheet exits the left edge, and three small rectangular rock-steel fragments touch the upper crop at unequal x positions. Combined bbox must exceed 97% width and 93% height. Keep the right half much denser than the left and never form two opposed lobes, feathers, petals, an X, or a center core. F10 is at least 35% weaker; the left sheet exits and the right wedge sinks.",
    negative: "wings, feathers, petals, butterfly, opposed lobes, central core, X, radial burst, biological silhouette, isolated center impact",
  }],
  ["warrior_t17_c05", {
    grammar: "NOMADIC_NINE_HIT_ROUTE_WIDE",
    prompt: "Second-pass correction for ‘전쟁왕 광란’. Keep nine visibly different moving contacts. Hits 1-8 visit upper-left, far-right, lower-left, top-right, and lower-middle without sharing a center. F09 is the ninth and unique maximum at x=82%, y=36%: one near cut exits through the right and top edges, while three older unequal wakes remain separately cropped at the left edge, bottom-left, and bottom-right. None of the four lanes may cross another. Combined bbox exceeds 97% width and 93% height through spatial distribution, not enlargement. F10 is at least 35% weaker and only the ninth wake persists strongly; all older wakes strictly fade.",
    negative: "central X, crossing slashes, shared center, central starburst, flurry flower, spiral, cyclone, repeated identical path",
  }],
  ["warrior_t18_c04", {
    grammar: "RIDGELINE_AVALANCHE_WIDE",
    prompt: "Second-pass correction for ‘만산 대붕괴’. Preserve the sequential upper-right to lower-left avalanche. At F09 use three independent curtains: a near upper-right ridge cropped by top and right folding toward bottom center, a middle curtain exiting the bottom-left edge, and a delayed far ridge cropped at the upper-left edge. Add unequal boulders at all four edge regions. Combined bbox exceeds 98% width and 95% height, but transparent diagonal canyons remain between curtains. The brightest fracture front stays near x=68%, y=46%. No central mountain bloom or opaque slab. F10 is at least 35% weaker and every curtain continues downward-left.",
    negative: "isolated center avalanche, empty side margins, central mountain flower, white opaque slab, mirrored ridges, circular dome",
  }],
  ["warrior_t20_c02", {
    grammar: "TRIPLE_OFFSET_WORLD_CRUSH_WIDE",
    prompt: "Second-pass correction for final skill ‘무쌍 대분쇄’. Make three offset contacts unmistakable and viewport-filling without a singular center. At F09 the far-left scar at x=16% reaches the left and top crops, the middle scar at x=54% splits a low shelf through the bottom crop, and the largest near contact at x=86% drives a colossal angular slab through right, top-right, and bottom-right crops. The three scars remain separated by transparent channels and never merge into one burst. Combined bbox exceeds 99% width and 96% height; no single slab exceeds 55% width. Use sovereign gold, white-hot ivory, black obsidian, disciplined crimson, and strong depth contrast. F10 is at least 40% weaker and all three zones only sink, slide outward, cool, and vanish.",
    negative: "central singularity, merged center blast, one giant object, isolated right cluster, empty left third, radial flower, symmetric emblem, repeated crater",
  }],
]);

const commonPrompt = "Use case: stylized-concept. Asset type: production 2D combat VFX overlay sprite sheet for AlarmQuest. This is a second-pass correction based only on textual QA defects; do not use any rejected PNG as an image reference and do not imitate existing AlarmQuest VFX. Create exactly 16 chronological frames in a strict 4-column by 4-row row-major sheet. Each cell is an independent 361:160 wide combat viewport; final sheet 1444:640; Detailed premium Korean fantasy action-RPG rendering; transparent RGBA; fixed camera; clean alpha; smooth high-resolution facets; no pixel art. Preserve the skill-specific off-center origin, path, contact, depth order, and exit edges. Peak F09 must be the unique maximum and must combine at least three separately scaled foreground, middle, and far-depth components. At F09 the combined alpha bounding box must reach within 2% of both left and right edges and within 5% of both top and bottom edges, while transparent channels remain between components. Fill the viewport by distributing multiple natural-depth elements and cropping them at different edges, never by stretching, scaling, or painting one object across the frame. F01 mean alpha below 2% and occupancy below 8%. F08 is subordinate. F10 is at least 35% weaker than F09, except the final skill requires 40%. F11-F16 strictly lose energy with no reconstruction; F16 mean alpha below 1.5% and occupancy below 6%. Every frame is separately clipped with no neighboring-cell band. Visual effect only; no character, enemy, scenery, UI, text, or fully rendered weapon.";

const negativePrompt = "pixel art, retro sprite, nearest-neighbor, low resolution, rejected sheet as visual reference, existing AlarmQuest VFX, character, warrior body, enemy, monster, weapon, scenery, battlefield, UI, HUD, text, letters, numbers, logo, watermark, frame label, grid, border, gutter, padding, opaque background, black background, white background, checkerboard, universal center anchor, central burst, center star, X, cross, radial flower, mirrored wings, butterfly, feathers, petals, symmetric crown, circular lens, reticle, ring, dome, vortex, spiral, orbit, copy-pasted symmetry, one giant scaled object, horizontal stretch, vertical stretch, panorama smear, empty left margin at F09, empty right margin at F09, empty top margin at F09, empty bottom margin at F09, peak occupied width below ninety-six percent, peak occupied height below ninety percent, opaque full-frame paint, rectangular alpha panel, adjacent frame residue, top image band, bottom image band, duplicated frame, missing frame, wrong order, camera motion, late second peak, post-peak reconstruction, broad F01, broad F16, excessive whiteout, muddy colors, jagged alpha";

const skills = [];
for (const [catalogId, correction] of corrections) {
  const original = source.skills.find((entry) => entry.catalogId === catalogId);
  if (!original) throw new Error(`${catalogId} missing from first-pass manifest.`);
  skills.push({
    catalogId,
    level: original.level,
    name: original.name,
    grammar: correction.grammar,
    generationPrompt: `${correction.prompt} Individual exclusions: ${correction.negative}. Do not render the skill name, a character, an enemy, or UI text.`,
  });
}

const output = {
  schemaVersion: 1,
  templateOnly: false,
  scope: "Warrior spatial-diversity second pass for nine first-pass QA failures",
  sourcePolicy: {
    existingVfxIgnored: true,
    rejectedSheetsAreNotImageReferences: true,
    qaInputs: ["catalogId", "skill name", "first-pass textual QA defects", "spatial grammar"],
  },
  gateway: source.gateway,
  settings: source.settings,
  commonPrompt,
  negativePrompt,
  skills,
};

const outputPath = resolve(root, "warrior-spatial-diversity-second-pass-sprite-prompts.json");
await writeFile(outputPath, `${JSON.stringify(output, null, 2)}\n`, "utf8");
process.stdout.write(`${skills.length}\t${outputPath}\n`);
