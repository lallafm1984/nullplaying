#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));

const designs = [
  {
    catalogId: "warrior_t01_c05",
    sourceManifest: "warrior-flurry-sprite-prompts.json",
    grammar: "ROUGH_ZIGZAG_CASCADE",
    prompt: "Rebuild ‘거친 연속참’ as ROUGH_ZIGZAG_CASCADE, a readable three-hit rough slash sequence that never converges on one center point. F04: one chipped steel pressure cut enters from the upper-left edge and ends near x=64%, y=38%. F07: a shorter reverse cut rises from the lower-right edge and exits at x=32%, y=31%. F08 is the subordinate crossed wake, still visibly uneven and open. F09 is the unique third hit: one broad broken descending cut starts outside the upper-right edge, crosses the right third, and tears out through the lower-middle edge; its strongest contact sits near x=72%, y=58%, not screen center. Preserve three separate trajectories and three separate impact notches. Palette: cold steel, dirty bronze, sparse amber sparks, restrained ivory contact light. Peak coverage must come from three natural-depth strokes cropped by different edges, not a radial burst or one enlarged blob. F10 is at least 30% weaker than F09; later frames only shed a few unequal chips along the last path.",
    negative: "central slash flower, centered storm, spiral slash blob, beige smoke blob, radial blade fan, symmetrical crossed blades, permanent central spark",
  },
  {
    catalogId: "warrior_t05_c04",
    sourceManifest: "warrior-earth-sprite-prompts.json",
    grammar: "LOWER_LEFT_SHEAR_LIFT",
    prompt: "Rebuild ‘지반 내려찍기’ as LOWER_LEFT_SHEAR_LIFT. The invisible downward force lands off-center at x=27%, y=82% in F07. F08 shows a compact lower-left compression shelf and one long subsurface fault traveling diagonally toward x=73%, y=61%. F09 is the unique maximum: three unequal ground plates hinge upward in sequence from left foreground to right middle distance while a low dust sheet escapes only toward the right edge. The left impact shelf is cropped by the bottom and left edges; a single far-right plate remains much smaller. No circular crater, ring, bowl, central pillar, or symmetric rock crown. Palette: ochre stone, dark umber strata, narrow amber fault light, sparse ivory dust. F10 is at least 30% weaker and the fault only settles and loses debris afterward.",
    negative: "center crater, circular ground bowl, radial ground explosion, symmetric rock crown, central vertical pillar, concentric shock rings, mirrored plates",
  },
  {
    catalogId: "warrior_t06_c02",
    sourceManifest: "warrior-crush-sprite-prompts.json",
    grammar: "UPPER_RIGHT_WEIGHT_DROP",
    prompt: "Rebuild ‘거인철퇴’ as UPPER_RIGHT_WEIGHT_DROP without drawing a literal hammer. A colossal rectangular pressure shadow appears only along the upper-right crop in F04-F06. Its oblique weight descends toward an off-center contact at x=69%, y=76%. F08 is the subordinate first buckle: the right ground shelf dents and two bronze compression seams angle toward the lower-left. F09 is the unique maximum: the unseen mass drives one thick black-steel pressure face through the upper-right edge while the ground folds into three unequal stepped slabs extending toward x=18%, y=88%. Keep the left half as trailing recoil, not a mirrored impact. Palette: blackened steel, burnished bronze, deep rust, concentrated ivory impact light near the right contact. No vertical center beam, crater, dome, circular ring, or radial rocks. F10 is at least 30% weaker and all later material drops, slides left, and fades.",
    negative: "literal hammer, centered hammer strike, central vertical beam, symmetrical crater, circular ring, dome, radial rocks, mirrored ground plates",
  },
  {
    catalogId: "warrior_t08_c04",
    sourceManifest: "warrior-earth-sprite-prompts.json",
    grammar: "DIAGONAL_FAULT_RUN",
    prompt: "Rebuild ‘지축 파쇄타’ as DIAGONAL_FAULT_RUN. A thin buried fracture begins at x=8%, y=88% and advances frame by frame toward x=92%, y=24%; it must visibly travel along this lower-left to upper-right diagonal instead of expanding from center. F07 reaches the middle distance. F08 is the subordinate long fault opening with three staggered basalt teeth on the lower-left half. F09 is the unique maximum when the fault reaches and shears through the upper-right crop, throwing one large foreground slab off the lower-left edge and several much smaller fragments off the top-right. The brightest fault segment is near x=76%, y=39%. Palette: iron-brown strata, burnished gold fissure light, disciplined rust-red accents, narrow ivory dust. No crater, radial bloom, ring, center star, symmetric rock fan, or circular ground wave. F10 is at least 30% weaker; the opened diagonal only cools from upper-right back toward lower-left.",
    negative: "central crater, radial bloom, circular shock wave, center star, symmetric rock fan, vertical eruption, mirrored fault, closed ring",
  },
  {
    catalogId: "warrior_t10_c01",
    sourceManifest: "warrior-cleave-sprite-prompts.json",
    grammar: "OFF_AXIS_WALL_SHEAR",
    prompt: "Rebuild ‘성벽 양단’ as OFF_AXIS_WALL_SHEAR. Depict only abstract fortress-scale material pressure, not a scenery wall. From F03 a massive beveled steel-stone plane occupies the left 42% of the viewport while a smaller receding buttress sits in the upper-right distance. F07 draws one razor-thin diagonal shear from x=18%, y=16% to x=77%, y=91%. F08 is the subordinate separation; F09 is the unique maximum when the near left plane slides down-left out of frame and the far right section snaps up-right, exposing an irregular bright cut surface whose strongest point is near x=61%, y=63%. The two sides must differ in thickness, angle, depth, color, motion, and fracture size. Palette: dark fortress steel, muted stone, royal-gold edge heat, restrained crimson sparks, ivory cut light. No centered butterfly, mirrored crescent gates, circular lens, X, cross, radial starburst, or nearly opaque full-frame panel. F10 is at least 30% weaker and both sections continue separating without recombining.",
    negative: "mirrored crescent gates, butterfly silhouette, centered split, symmetric wall halves, circular lens, X shape, cross shape, radial starburst, full-frame opaque panel",
  },
  {
    catalogId: "warrior_t14_c04",
    sourceManifest: "warrior-earth-sprite-prompts.json",
    grammar: "CORNER_TO_CORNER_CRUST_WAVE",
    prompt: "Rebuild ‘지각 대폭쇄’ as CORNER_TO_CORNER_CRUST_WAVE. F03-F06 establish a low geological shelf entering from the lower-right corner, with its leading fracture moving toward the left middle plane. F07 reaches x=58%, y=70%. F08 is the subordinate three-plate chain. F09 is the unique maximum: a tall foreground crust fin erupts only from the lower-right crop, a lower middle plate tears across x=52% toward x=21%, and a distant dust fault exits the left edge near y=43%. Each depth has a different direction and no shared radial origin. Palette: black basalt, royal-gold fault edges, disciplined crimson seams, ivory dust. Keep transparent air gaps between slabs; never create a circular crater, flower, crown, central pillar, or rectangular alpha band. F10 is at least 30% weaker and the wave continues off the left edge while the right fin collapses.",
    negative: "center crater, rock flower, symmetric crown, central pillar, radial spokes, mirrored plates, opaque rectangular strip, top image band, bottom image band",
  },
  {
    catalogId: "warrior_t16_c02",
    sourceManifest: "warrior-crush-sprite-prompts.json",
    grammar: "ASYMMETRIC_OVERLORD_SMASH",
    prompt: "Rebuild ‘패왕 대강타’ as ASYMMETRIC_OVERLORD_SMASH. A black-gold pressure mass enters as a steep diagonal crop from the upper-left, aimed at x=74%, y=72%. F07 shows the target ground bending before contact. F08 is the subordinate contact with one narrow crimson compression slash. F09 is the unique maximum: the off-center right impact drives a huge near-depth obsidian wedge out through the bottom-right edge, a broad gold pressure sheet skims toward the left edge, and only two small fragments rise into the upper-middle distance. Make the right half brutally dense and the upper-left recoil visibly thinner. Peak coverage comes from depth and cropping, not a central vortex. Palette: black steel, sovereign gold, deep crimson, white-hot contact light. No whirlpool, dome, vertical central blow, centered crater, ring, radial fan, or mirrored debris. F10 is at least 30% weaker and the pressure sheet exits left while the right wedge sinks and fades.",
    negative: "central whirlpool, vortex, dome, vertical center impact, centered crater, ring, radial fan, mirrored debris, symmetric ground explosion",
  },
  {
    catalogId: "warrior_t17_c05",
    sourceManifest: "warrior-flurry-sprite-prompts.json",
    grammar: "NOMADIC_NINE_HIT_ROUTE",
    prompt: "Rebuild ‘전쟁왕 광란’ as NOMADIC_NINE_HIT_ROUTE, a nine-hit barrage whose contact location keeps moving and never forms one central storm. Hits 1-2 flick across the upper-left quadrant in F02-F03; hits 3-4 cut the far-right middle plane in F04-F05; hits 5-6 rake the lower-left foreground in F06-F07; hit 7 is a short top-right drop in F08; hits 8-9 combine only in F09 as two unequal near-depth cuts, one from the right edge to lower-middle and one from the bottom-left edge toward x=62%, y=28%. F09 is the unique maximum but retains open transparent gaps and no shared center point. Every hit differs in angle, length, thickness, depth, color balance, and recoil. Palette: black steel, sovereign gold, disciplined crimson afterimages, white-hot ivory edges. No spiral, cyclone, X, flower, mirrored pinwheel, center star, or circular orbit. F10 is at least 30% weaker and only the last two off-axis wakes remain; later frames strictly disperse.",
    negative: "centered flurry, spiral slash storm, cyclone, X shape, flower, mirrored pinwheel, central star, circular orbit, repeated identical slash path",
  },
  {
    catalogId: "warrior_t18_c04",
    sourceManifest: "warrior-earth-sprite-prompts.json",
    grammar: "RIDGELINE_AVALANCHE",
    prompt: "Rebuild ‘만산 대붕괴’ as RIDGELINE_AVALANCHE. Do not use ten domes or a central collapse. F02-F05 reveal three unequal abstract mountain-pressure ridges cropped along the upper-right, upper-middle, and far-left edges. They fail sequentially from right to left. F07 sends the first avalanche curtain diagonally down toward x=61%, y=74%. F08 is the subordinate second curtain across the right half. F09 is the unique maximum when the largest upper-right ridge folds toward the lower-left foreground, a middle ridge breaks toward the bottom edge, and the far-left ridge remains small and delayed. The brightest fracture front lies near x=68%, y=46%, with large right foreground boulders and tiny left-distance debris. Palette: obsidian ridges, sovereign gold fracture light, deep crimson seams, dense ivory dust. No white opaque slab, centered crater, circular dome, radial mountain flower, mirror symmetry, or horizontal frame band. F10 is at least 30% weaker and all ridges continue downward-left without rebuilding.",
    negative: "ten domes, central mountain collapse, white opaque slab, centered crater, circular dome, radial mountain flower, mirrored ridges, horizontal frame band, adjacent cell residue",
  },
  {
    catalogId: "warrior_t20_c02",
    sourceManifest: "warrior-crush-sprite-prompts.json",
    grammar: "TRIPLE_OFFSET_WORLD_CRUSH",
    prompt: "Rebuild final skill ‘무쌍 대분쇄’ as TRIPLE_OFFSET_WORLD_CRUSH, the strongest crush in this batch without a central singularity. Three colossal invisible pressure slabs descend at different depths and x positions: a far slab at x=22% contacts in F06, a middle slab at x=57% contacts in F08, and the largest near slab at x=84% crashes through the upper-right crop in F09. F08 is the subordinate second impact. F09 is the unique maximum: the right near slab pulverizes a deep foreground shelf out through bottom-right, the middle plate buckles toward lower-left, and the far-left impact leaves only a thin delayed gold fault. Use three clearly separated contact scars and uneven debris scales; the brightest contact is near x=81%, y=69%. Peak width and height exceed 94% and 88% through multi-depth cropping, yet transparent gaps remain between slabs. Palette: white-hot ivory, sovereign gold, black obsidian, disciplined crimson, sparse orange sparks. No center core, dome, vortex, ring, radial flower, symmetric three-prong emblem, or repeated crater. F10 is at least 35% weaker; F11-F16 only sink, slide outward, cool, and vanish with no second peak.",
    negative: "central singularity, center core, dome, vortex, ring, radial flower, symmetric three-prong emblem, repeated crater, central vertical beam, mirrored world collapse",
  },
];

const commonPrompt = "Use case: stylized-concept. Asset type: production 2D combat VFX overlay sprite sheet for AlarmQuest. Create each requested warrior effect from scratch and do not imitate any rejected sheet or existing AlarmQuest VFX. Create exactly 16 chronological frames in a strict 4-column by 4-row row-major sprite sheet. Every cell is a wide 361:160 combat viewport with one fixed camera and transparent RGBA background. Detailed premium Korean fantasy action-RPG VFX, hand-painted high-resolution 2D rendering, smooth tonal shading, crisp silhouettes, rich material facets, controlled bloom, and clean alpha. This revision program explicitly rejects one universal center anchor: each individual generation prompt defines a different off-center origin, path, impact location, depth order, and exit edge. Do not pull unrelated elements back toward x=50%, do not create a central burst to fill the frame, and do not mirror one side onto the other. F01 is nearly transparent with mean alpha below 2% and occupancy below 8%. F02-F07 clearly develop the authored path. F08 is a subordinate main contact. F09 is the unique maximum with the greatest mean alpha and occupancy by a clear margin. F10 must already be at least 30% weaker than F09. F11-F16 only separate, travel onward, lose fragments, dim, and disperse with strictly decreasing energy; F16 remains below 1.5% mean alpha and 6% occupancy. Fill peak frames through several natural-depth elements and intentional edge cropping, never by stretching one object or painting a background. Every 361x160 cell is independently clipped with clean transparent top and bottom edges and zero neighboring-frame residue. Visual effect only: no warrior body, enemy body, or fully rendered weapon. No camera pan, zoom, rotation, shake, or loop reset. No grid, labels, captions, borders, gutters, or padding.";

const negativePrompt = "pixel art, pixels, retro sprite, low resolution, nearest-neighbor edges, existing AlarmQuest VFX, copied asset, traced reference, rejected sprite sheet, character, warrior, human body, enemy, monster, fully rendered weapon, background scenery, battlefield, landscape, floor tile, UI, HUD, health bar, text, letters, numbers, logo, signature, watermark, frame number, caption, grid line, border, gutter, padding, opaque background, black background, white background, checkerboard background, central burst, center-origin explosion, permanent center light, permanent center star, universal x fifty anchor, radial spokes, radial flower, mirrored wings, butterfly silhouette, symmetric crown, circular lens, reticle, bullseye, concentric rings, closed circle, oval, dome, vortex, spiral, orbit, figure-eight, infinity symbol, X-shaped emblem, cross-shaped emblem, copy-pasted symmetry, peak left-right symmetry above forty percent, one giant scaled object, flat panorama smear, horizontal stretch, vertical stretch, full-frame opaque paint, rectangular alpha panel, adjacent frame residue, horizontal image band, top image band, bottom image band, repeated identical frames, duplicated frame, missing frame, wrong frame order, inconsistent camera, inconsistent scale, late second peak, post-peak reconstruction, broad F01, broad F16, excessive whiteout, foggy silhouette, muddy colors, jagged alpha";

const cache = new Map();
async function load(name) {
  if (!cache.has(name)) cache.set(name, JSON.parse(await readFile(resolve(root, name), "utf8")));
  return cache.get(name);
}

let settings = null;
const skills = [];
for (const design of designs) {
  const source = await load(design.sourceManifest);
  settings ??= source.settings;
  const skill = source.skills.find((entry) => entry.catalogId === design.catalogId);
  if (!skill) throw new Error(`${design.catalogId} not found in ${design.sourceManifest}.`);
  skills.push({
    catalogId: skill.catalogId,
    level: skill.level,
    name: skill.name,
    grammar: design.grammar,
    generationPrompt: `${design.prompt} Individual exclusions: ${design.negative}. Do not render the skill name, a character, an enemy, or UI text.`,
  });
}

const output = {
  schemaVersion: 1,
  templateOnly: false,
  scope: "Warrior spatial-diversity rebuild for ten user-rejected skills",
  sourcePolicy: {
    existingVfxIgnored: true,
    rejectedSheetsAreNotReferences: true,
    identityInputs: ["catalogId", "Korean skill name", "level", "power stage", "hit count", "new spatial grammar"],
  },
  gateway: "http://127.0.0.1:4317",
  settings,
  commonPrompt,
  negativePrompt,
  skills,
};

const outputPath = resolve(root, "warrior-spatial-diversity-revision-sprite-prompts.json");
await writeFile(outputPath, `${JSON.stringify(output, null, 2)}\n`, "utf8");
process.stdout.write(`${skills.length}\t${outputPath}\n`);

