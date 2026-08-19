#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));

const revisionRules = [
  {
    catalogId: "ranger_t01_c01",
    sourceManifest: "ranger-horizon-pinpoint-sprite-prompts.json",
    correction: "QA revision: completely replace the rejected permanent gold center glint, blue circular targeting lens, repeated three-way radial spokes, mirrored ice-glass wing burst, 84.27%/92.26% F08/F09 symmetry, post-peak bright-center drift to x=41.25%/40.35%/37.82%, and broad F01/F16 residue. Depict Aimed Shot as impossible depth accuracy without drawing an arrow, bow, archer, target reticle, or horizon scenery: one hair-thin mercury-blue alignment seam appears already aimed through the upper-right far plane toward a fixed x=50% contact; at F08 it compresses three deliberately unequal transparent pressure facets from different depths, and F09 converts them into one colossal cropped blue-white foreground rupture only along the lower-left edge plus one short pale-gold counter-fracture toward the upper-left middle plane. Add exactly three small irregular frost-glass chips at unequal depths. Every structure differs in angle, length, width, scale, depth, timing, brightness, and recoil; no paths are evenly spaced or opposed. No permanent glint, center star, circular lens, reticle, crosshair, scope, bullseye, horizon line, radial spokes, trident, flower, mirrored wings, paired crescents, evenly spaced rays, symmetric starburst, circle, oval, ring, loop, spiral, bow, figure-eight, or infinity mark. The contact is only a brief point-sized blue-gold fault fixed within x=48%-52% at F08-F09 and absent otherwise. Every 361x160 cell is independently clipped with clean transparent top and bottom edges and zero neighboring-frame residue. F01 contains at most one tiny dim point and one hairline trace with mean alpha below 2% and occupancy below 8%. F08 is a subordinate alignment contact. F09 is the unique precision rupture maximum with at least 94% width and 88% height coverage from three unequal depths and one-sided cropping, greatest mean alpha and occupancy by a clear margin, and peak left-right alpha symmetry below 25%. F10 is already at least 30% weaker than F09. F11 through F16 only separate, recede, lose fragments, dim, and disperse with strictly decreasing mean alpha and occupancy; no reticle or lens reconstruction, the brightest point stays within x=48%-52%, and F16 remains below 1.5% mean alpha and 6% occupancy.",
    negative: "arrow, bow, archer, target, target reticle, scope, bullseye, crosshair, horizon scenery, permanent gold center glint, central star, blue circular targeting lens, three-way radial spokes, trident, mirrored ice-glass wing burst, paired crescents, symmetric starburst, evenly spaced rays, butterfly, flower, circle, oval, ring, loop, spiral, orbit, bow knot, figure-eight, infinity symbol, peak symmetry above twenty-five percent, bright core outside x forty-eight to fifty-two percent, post-peak center drift, broad effect in F01, broad effect in F16, F16 occupancy above six percent, adjacent frame residue, horizontal image band, top image band, bottom image band",
  },
  {
    catalogId: "ranger_t02_c01",
    sourceManifest: "ranger-horizon-pinpoint-sprite-prompts.json",
    correction: "QA revision: completely replace the rejected left-to-right horizontal beam tunnel, repeated circular glass lenses, permanent gold center glint, mirrored blue-gold wing burst, 87.96% symmetric F09 peak, broad F01/F16 residue, and post-peak bright-center drift to x=39.93%/38.39%. Depict Piercing Shot as a centered impossible-depth penetration with no projectile traveling across the screen: three deliberately unequal translucent pressure planes are already aligned in near, middle, and far depth around a fixed x=50% contact, each tilted on a different non-opposed angle. F08 compresses them inward without forming a tunnel; F09 punctures through the viewer as one colossal cropped cobalt-black foreground slab only along the upper-left edge, one narrower white-gold pressure tear through the lower-right middle plane, and two tiny far-depth blue notches. Every element differs in angle, length, width, scale, depth, timing, brightness, and recoil. No arrow, bolt, horizontal beam, left-to-right travel, side-scrolling shot, tunnel, circular lens, scope, reticle, bullseye, permanent glint, center star, flower, radial fan, mirrored wings, paired crescents, evenly spaced rays, symmetric starburst, circle, oval, ring, loop, spiral, bow, figure-eight, or infinity mark. The contact is only a brief point-sized blue-gold fault fixed within x=48%-52% at F08-F09 and absent otherwise. Every 361x160 cell is independently clipped with clean transparent top and bottom edges and zero neighboring-frame residue. F01 contains at most one tiny dim point and one short trace with mean alpha below 2% and occupancy below 8%. F08 is a subordinate penetration contact. F09 is the unique through-depth rupture maximum with at least 94% width and 88% height coverage from three unequal depths and one-sided cropping, greatest mean alpha and occupancy by a clear margin, and peak left-right alpha symmetry below 25%. F10 is already at least 30% weaker than F09. F11 through F16 only separate, recede into different depth planes, lose fragments, dim, and disperse with strictly decreasing mean alpha and occupancy; no tunnel or lens reconstruction, the brightest point stays within x=48%-52%, and F16 remains below 1.5% mean alpha and 6% occupancy.",
    negative: "arrow, bolt, projectile, horizontal beam, left-to-right travel, side-scrolling shot, horizontal tunnel, repeated circular glass lenses, scope, target reticle, bullseye, permanent gold center glint, mirrored blue-gold wing burst, flower, radial fan, paired crescents, symmetric starburst, evenly spaced rays, butterfly, circle, oval, ring, loop, spiral, orbit, bow knot, figure-eight, infinity symbol, peak symmetry above twenty-five percent, bright core outside x forty-eight to fifty-two percent, post-peak center drift, broad effect in F01, broad effect in F16, F16 occupancy above six percent, adjacent frame residue, horizontal image band, top image band, bottom image band",
  },
  {
    catalogId: "ranger_t03_c01",
    sourceManifest: "ranger-horizon-pinpoint-sprite-prompts.json",
    correction: "QA revision: completely replace the rejected three-way radial spokes, permanent gold center star, mirrored blue crystal feather flower, opaque-looking 99.82%-100% alpha-filled F08-F10 peak, 88.01%/91.24% F08/F09 symmetry, broad F01 residue, and post-peak bright-center swings to x=42.36%/53.49%/40.76%. Depict Hawk-Eye Pierce without any eye, bird, feather, arrow, scope, or reticle: one nearly invisible pale-blue parallax fault appears from the lower-right far plane toward a fixed x=50% contact; two much shorter asymmetrical amber and deep-cobalt notches occupy the upper-left and lower-left middle depths at unrelated angles. F08 aligns these unequal depth cues without a radial emblem. F09 releases one colossal cropped navy-glass foreground plate only through the upper-right edge and one narrow white-gold pressure incision through the lower-left edge. All elements differ in angle, length, width, scale, depth, timing, brightness, and recoil. No eagle, hawk, eye, pupil, iris, feather, wing, arrow, scope, reticle, bullseye, permanent star, radial spokes, trident, flower, mirrored crystal petals, paired crescents, evenly spaced rays, symmetric starburst, circle, oval, ring, loop, spiral, bow, figure-eight, or infinity mark. Keep transparent negative space between all materials; never cover 100% of the cell with alpha or paint a full-frame backdrop. The contact is only a brief point-sized blue-gold fault fixed within x=48%-52% at F08-F09 and absent otherwise. Every 361x160 cell is independently clipped with clean transparent top and bottom edges and zero neighboring-frame residue. F01 contains at most one tiny dim point and one short trace with mean alpha below 2% and occupancy below 8%. F08 is a subordinate parallax alignment. F09 is the unique hawk-eye rupture maximum with at least 94% width and 88% height coverage from three unequal depths, visible transparent gaps, and one-sided cropping, greatest mean alpha and occupancy by a clear margin, and peak left-right alpha symmetry below 20%. F10 is already at least 30% weaker than F09. F11 through F16 only separate, recede, lose fragments, dim, and disperse with strictly decreasing mean alpha and occupancy; no radial flower or central star reconstruction, the brightest point stays within x=48%-52%, and F16 remains below 1.5% mean alpha and 6% occupancy.",
    negative: "eagle, hawk, bird, eye, pupil, iris, feather, wing, arrow, scope, target reticle, bullseye, permanent gold center star, three-way radial spokes, trident, mirrored blue crystal feather flower, radial petals, paired crescents, symmetric starburst, evenly spaced rays, butterfly, circle, oval, ring, loop, spiral, orbit, bow knot, figure-eight, infinity symbol, opaque full-frame background, one hundred percent alpha occupancy, painted backdrop, peak symmetry above twenty percent, bright core outside x forty-eight to fifty-two percent, post-peak center swing, broad effect in F01, adjacent frame residue, horizontal image band, top image band, bottom image band",
  },
  {
    catalogId: "ranger_t04_c01",
    sourceManifest: "ranger-horizon-pinpoint-sprite-prompts.json",
    correction: "QA revision: completely replace the rejected permanent gold center star, circular targeting lens, straight vertical and horizontal crosshair rays, repeated radial spokes, mirrored ice-glass flower, nearly opaque 100% alpha-filled F08-F10 peak, 95.30%/98.68% F08/F09 symmetry, and post-peak bright-center drift to x=59.26%/60.92%/56.34%. Depict Weak-Point Pierce as an asymmetrical depth fault that exposes one brief vulnerability without drawing a target or projectile: a dim ultramarine compression crease approaches the fixed x=50% contact from the upper-left far plane, while one much shorter amber fracture and two irregular translucent steel-blue facets occupy unrelated lower-right and upper-right depths. F08 compresses these unequal planes around open transparent gaps. F09 ruptures toward the viewer as one colossal cropped midnight-blue foreground wedge only through the lower-left edge, a narrow white-gold splinter through the upper-right middle plane, and three tiny cobalt chips at unequal depths. Every element differs in angle, length, width, scale, depth, timing, brightness, and recoil. No arrow, bow, target, target reticle, scope, crosshair, bullseye, permanent glint, center star, circular lens, straight horizontal or vertical axis ray, radial spokes, mirrored petals, paired crescents, evenly spaced rays, symmetric starburst, flower, butterfly, circle, oval, ring, loop, spiral, figure-eight, or infinity mark. Keep visible transparent negative space between all materials; never paint the full frame or create a backdrop. The contact is only a brief point-sized blue-gold pressure fault fixed within x=48%-52% at F08-F09 and absent otherwise. Every 361x160 cell is independently clipped with clean transparent top and bottom edges and zero neighboring-frame residue. F01 contains at most one tiny dim trace with mean alpha below 2% and occupancy below 8%. F08 is a subordinate vulnerability exposure. F09 is the unique weak-point rupture maximum with at least 94% width and 88% height coverage from three unequal depths, open transparent gaps, and strongly one-sided cropping, greatest mean alpha and occupancy by a clear margin, and peak left-right alpha symmetry below 20%. F10 is already at least 30% weaker than F09. F11 through F16 only separate, recede, lose fragments, dim, and disperse with strictly decreasing mean alpha and occupancy; no target or star reconstruction, the brightest point stays within x=48%-52%, and F16 remains below 1.5% mean alpha and 6% occupancy.",
    negative: "arrow, bow, archer, projectile, target, target reticle, scope, bullseye, crosshair, permanent gold center star, permanent center glint, circular targeting lens, straight vertical ray, straight horizontal ray, radial spokes, trident, mirrored ice-glass flower, radial petals, paired crescents, symmetric starburst, evenly spaced rays, butterfly, circle, oval, ring, loop, spiral, orbit, bow knot, figure-eight, infinity symbol, opaque full-frame background, one hundred percent alpha occupancy, painted backdrop, peak symmetry above twenty percent, bright core outside x forty-eight to fifty-two percent, post-peak center drift, broad effect in F01, adjacent frame residue, horizontal image band, top image band, bottom image band",
  },
];

const sourceCache = new Map();
async function sourceManifest(fileName) {
  if (!sourceCache.has(fileName)) {
    sourceCache.set(fileName, JSON.parse(await readFile(resolve(scriptDir, fileName), "utf8")));
  }
  return sourceCache.get(fileName);
}

const skills = [];
let settings = null;
let commonPrompt = "";
let negativePrompt = "";
for (const rule of revisionRules) {
  const source = await sourceManifest(rule.sourceManifest);
  const sourceSkill = source.skills.find((skill) => skill.catalogId === rule.catalogId);
  if (!sourceSkill) throw new Error(`${rule.catalogId} not found in ${rule.sourceManifest}.`);
  settings ??= source.settings;
  commonPrompt ||= `${source.commonPrompt} Every QA revision instruction in the individual generation prompt is authoritative and must override any tendency toward reticles, radial symmetry, center drift, or excessive start/end residue.`;
  negativePrompt ||= source.negativePrompt;
  negativePrompt = `${negativePrompt}, ${rule.negative}`;
  skills.push({
    ...sourceSkill,
    generationPrompt: `${sourceSkill.generationPrompt} ${rule.correction}`,
  });
}

const output = {
  schemaVersion: 1,
  templateOnly: false,
  scope: "Ranger visual QA revisions rejected from web ready promotion",
  sourcePolicy: {
    existingVfxIgnored: true,
    rejectedSheetsAreNotReferences: true,
    identityInputs: ["catalogId", "Korean skill name", "level", "power stage", "hit count", "QA defect description"],
  },
  gateway: "http://127.0.0.1:4317",
  settings,
  commonPrompt,
  negativePrompt,
  skills,
};

const outputPath = resolve(scriptDir, "ranger-quality-revision-sprite-prompts.json");
await writeFile(outputPath, `${JSON.stringify(output, null, 2)}\n`, "utf8");
process.stdout.write(`${skills.length}\t${outputPath}\n`);
