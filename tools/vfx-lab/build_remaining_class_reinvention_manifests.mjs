#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const skillsPath = resolve(scriptDir, "data/skills.json");
const payload = JSON.parse(await readFile(skillsPath, "utf8"));

const settings = {
  type: "spritesheet",
  model: "gpt-5.6-sol",
  mode: "detailed",
  frameWidth: 361,
  frameHeight: 160,
  frameCount: 16,
  columns: 4,
  rows: 4,
  preset: "attack",
  fps: 16,
  loop: false,
  autoReview: true,
  qualityTarget: 90,
  maxPasses: 4,
  useReference: false,
};

const classOpenings = {
  RANGER: "The animation must read as impossible ranger accuracy, distance control, weathercraft, and predatory timing without drawing an archer body, bow-holding hands, enemy, animal, or landscape.",
  MAGE: "The animation must read as monumental elemental spellcraft and reality manipulation without drawing a mage body, casting hands, staff, enemy, scenery, or a generic circular explosion.",
  CLERIC: "The animation must read as sacred revelation, judgment, purification, and incorporeal heavenly force without drawing a cleric body, praying hands, angel body, enemy, temple, or religious text.",
  PALADIN: "The animation must read as royal holy warfare, defense converted into impact, gravity, dawn, and an unbreakable oath without drawing a paladin body, weapon-holding hands, enemy, castle, or battlefield.",
};

const sharedOpening = "Use case: stylized-concept. Asset type: production 2D combat VFX overlay sprite sheet for AlarmQuest. Invent this family entirely from scratch using only the stable catalogId, Korean skill name, level, power stage, and hit count. Completely ignore and do not imitate, trace, remix, preserve, or use as reference any existing AlarmQuest VFX image, asset, animation, action tag, direction tag, palette, silhouette, or timing. Create exactly 16 chronological animation frames in a strict 4-column by 4-row, row-major sprite sheet. Every cell is one independent wide 361:160 combat viewport with the same fixed camera, scale, ground line, and structural target at x=50%, y=55%. Detailed premium Korean fantasy action-RPG VFX, hand-painted high-resolution 2D rendering, smooth tonal shading, clean silhouette hierarchy, rich translucent materials, controlled bloom, and clean alpha. Maximum centered impact density and natural screen coverage take priority; damage-number visibility does not matter.";

const sharedEnding = "At F08-F10, the combined near, middle, and far depth layers must occupy at least 90% of frame width and 82% of frame height, with naturally proportioned foreground fragments intentionally cropped by all four edges. Fill the viewport through perspective, overlapping depth, and edge cropping, never by scaling or stretching one object. F01 is nearly transparent anticipation. F02-F04 establish the family mechanism. F05-F07 accelerate and tighten around the fixed target. F08 is main contact. F09 is maximum impact. F10-F12 release separated fragments, wakes, membranes, or pressure through the viewer. F13-F14 retain a large readable afterimage. F15-F16 fade cleanly to transparency. Every frame must be distinct and continuous. No camera pan, camera zoom, camera rotation, camera shake, lateral target motion, moving vanishing point, side-scrolling attack, or loop reset. Transparent RGBA background. F01 and F16 must be mostly transparent. Visual effect only: no character body and no enemy body. Treat every 361x160 cell as a sealed final frame; never leak an adjacent cell or create a bottom image band. Do not render grid guides, frame labels, captions, borders, gutters, or padding.";

const negativePrompt = "pixel art, pixels, retro sprite, low resolution, nearest-neighbor edges, existing AlarmQuest VFX, copied game asset, traced reference, reused silhouette, character, human body, face, hands, legs, enemy, monster, angel body, animal body, gore, blood splatter, fully rendered bow, fully rendered arrow, fully rendered sword, fully rendered shield, fully rendered hammer, staff, background scenery, battlefield illustration, castle, temple, landscape, floor tile, user interface, HUD, health bar, text, Korean letters, English letters, numbers, rune letters, readable sigil text, logo, signature, watermark, frame number, caption, grid line, border, gutter, padding, opaque background, black background, white background, checkerboard background, tiny effect, miniature effect, small isolated center icon, empty peak corners, effect occupying less than eighty percent of peak frame, horizontal stretch, vertical stretch, flat panorama smear, rubber distortion, warped proportions, one giant scaled object, generic circular explosion, copy-pasted symmetry, mirrored butterfly silhouette, repeated identical frames, duplicated frame, missing frame, wrong frame order, inconsistent camera, inconsistent scale, unrelated secondary attack, left-to-right sprite translation, drifting target, moving vanishing point, camera shake, excessive whiteout, excessive bloom, foggy silhouette, muddy colors, motion blur hiding material detail, jagged alpha, haloed alpha, adjacent frame residue, next-frame image, previous-frame image, cross-cell continuation, bottom image band";

const stageBlueprints = [
  "a compact three-ray seed with one restrained foreground fragment",
  "a paired four-facet bracket separated across near and far depth",
  "three disciplined pressure ribs surrounding a narrow central seam",
  "an asymmetric five-point clasp with one delayed contact layer",
  "two interlocked crescent membranes and a faceted contact core",
  "a deep vertical vault crossed by four perspective splinters",
  "three cardinal planes plus one off-axis foreground wake",
  "two opposing depth funnels with six separated edge fragments",
  "a dense seven-facet crown that leaves the structural core visible",
  "three nested impact vaults and a wide residual fault",
  "five staggered pressure membranes folding through different depths",
  "a monumental central rib surrounded by eight independent shards",
  "three black-glass apertures framed by luminous material edges",
  "four sky-to-ground pressure planes and one viewer-facing rupture",
  "a sovereign halo broken into nine unequal natural fragments",
  "three full-depth layers that successively invert around the target",
  "a god-tier central fault with twelve separated foreground facets",
  "four end-time vaults overtaking every edge without whiteout",
  "three world-scale consequence waves grouped into readable beats",
  "an ultimate three-stage reality rupture with foreground, middle, and far consequences",
];

const families = [
  {
    heroClass: "RANGER", candidate: 0, slug: "ranger-horizon-pinpoint", grammar: "HORIZON_PINPOINT_RUPTURE", action: "SNIPE",
    motion: "Create distance without lateral travel: a hair-thin pressure shaft condenses from the fixed central vanishing point, stacks concentric lens-like air planes in depth, then punctures straight through the target toward the viewer. F08 is the precise contact; F09 unfolds the consequence into large split air prisms and a horizon-wide fault while the core stays fixed. No literal bow, projectile flight across the screen, scope UI, or landscape.",
    material: "compressed air glass, silver ballistic facets, restrained amber weak-point light, and deep blue perspective planes",
    paletteLow: "cold silver, blue steel, pale cyan air glass, restrained amber", paletteHigh: "white-hot silver, midnight blue, imperial cyan, royal gold, sparse crimson",
  },
  {
    heroClass: "RANGER", candidate: 1, slug: "ranger-skyburst-volley", grammar: "SKYBURST_VOLLEY_VAULT", action: "VOLLEY",
    motion: "Build countable bolt-pressure streaks as separate near, middle, and far trajectories that curve inward from above, below, and behind the fixed target instead of sliding left-to-right. Each hit stamps a small air-notch; F08-F09 combine the notches into a vaulted storm canopy that bursts toward the viewer. Use energetic shaft silhouettes, not fully rendered arrows.",
    material: "wind-carved ivory shafts, turquoise pressure wakes, translucent sky vaults, and faceted rain-like fragments",
    paletteLow: "ivory, turquoise, cool blue, muted green, sparse gold", paletteHigh: "white ivory, celestial cyan, storm blue, imperial gold, violet edge sparks",
  },
  {
    heroClass: "RANGER", candidate: 2, slug: "ranger-tempest-string", grammar: "TEMPEST_BOWSTRING_SINGULARITY", action: "TEMPEST",
    motion: "Express an invisible bowstring as two enormous curved wind membranes tensioning symmetrically in depth around the fixed center. Do not draw a bow or character. Successive wind beats tighten the membranes; F08 releases them into a centered pressure singularity and F09 tears the surrounding atmosphere into helical viewer-facing sheets. The target and camera never move.",
    material: "layered wind membranes, teal-white shear lines, condensed storm glass, and dark cyan vacuum seams",
    paletteLow: "teal, pale cyan, sea green, translucent white", paletteHigh: "celestial teal, white lightning, deep storm blue, royal green, sparse gold",
  },
  {
    heroClass: "RANGER", candidate: 3, slug: "ranger-predator-domain", grammar: "PREDATOR_SNARE_DOMAIN", action: "HUNT",
    motion: "Create a hunting domain from abstract claw-pressure arcs, scent-like tension filaments, and earthen anchor facets emerging from all four edges and several depths. Never draw an animal, literal trap, cage, paw, or floor. F08 snaps the domain shut at the fixed center; F09 releases a huge predatory pressure blossom and broken natural stone-glass anchors toward the viewer.",
    material: "bronze earth facets, dark forest tension lines, ochre claw-pressure crescents, and muted jade tracking membranes",
    paletteLow: "bronze, moss green, ochre, smoke gray, pale amber", paletteHigh: "ancient gold, deep forest jade, black bronze, ivory, cosmic violet accents",
  },
  {
    heroClass: "RANGER", candidate: 4, slug: "ranger-lunar-constellation", grammar: "LUNAR_CONSTELLATION_PIERCER", action: "CONSTELLATION",
    motion: "Arrange small lunar lens facets and star-pressure nodes across multiple depths around the fixed center. Countable hits connect only through fleeting luminous trajectories, never readable text or literal constellation drawings. F08 collapses the celestial nodes into one narrow moonlit puncture; F09 opens a vast layered starfield fault through the viewer without rendering a background sky.",
    material: "moon-silver crescents, indigo cosmic glass, violet star facets, and ivory-blue contact light",
    paletteLow: "moon silver, indigo, muted violet, pale blue", paletteHigh: "white moonlight, imperial violet, midnight indigo, cyan starlight, royal gold",
  },
  {
    heroClass: "MAGE", candidate: 0, slug: "mage-solar-forge", grammar: "SOLAR_FORGE_DETONATION", action: "PYRE",
    motion: "Forge layered fire as translucent molten membranes and pressure petals around a black-hot fixed core, not a generic fireball traveling across the screen. Each hit adds a distinct heat-shell beat. F08 compresses the shells; F09 detonates them into enormous near-camera fireglass plates, ember ribbons, and a readable dark core without a whiteout.",
    material: "molten orange fireglass, crimson heat membranes, black-hot seams, and ivory-gold contact light",
    paletteLow: "ember orange, crimson, charcoal, pale gold", paletteHigh: "solar gold, white-orange, deep crimson, black-hot violet, sparse cyan heat edges",
  },
  {
    heroClass: "MAGE", candidate: 1, slug: "mage-cryostasis-cathedral", grammar: "CRYOSTASIS_CATHEDRAL_SHATTER", action: "CRYOSTASIS",
    motion: "Grow monumental translucent ice ribs from independent depth planes around the fixed target, forming an abstract cathedral vault without scenery or architecture. Countable hits are separate frost-lock beats. F08 seals the vault; F09 shatters it toward the viewer into large natural ice facets and cold vapor membranes while the brightest core stays centered.",
    material: "blue-white crystalline ribs, deep sapphire glass, pale cyan frost membranes, and violet cold shadows",
    paletteLow: "pale cyan, ice blue, sapphire, translucent white", paletteHigh: "white frost, royal sapphire, glacial cyan, imperial violet, sparse silver",
  },
  {
    heroClass: "MAGE", candidate: 2, slug: "mage-thunder-circuit", grammar: "THUNDER_CIRCUIT_JUDGMENT", action: "THUNDER",
    motion: "Route countable lightning beats through an asymmetric three-dimensional circuit of floating conductive facets around the fixed center. No bolt should merely descend from a background sky or travel sideways. F08 closes the circuit at the target; F09 overvolts every depth layer into a dense white-violet branching rupture and separated conductive shards.",
    material: "violet-white lightning veins, cobalt conductive glass, black electric cavities, and cyan corona ribbons",
    paletteLow: "electric blue, violet, white, deep cobalt", paletteHigh: "white lightning, imperial violet, midnight cobalt, cyan corona, royal gold sparks",
  },
  {
    heroClass: "MAGE", candidate: 3, slug: "mage-arcane-geometry", grammar: "ARCANE_GEOMETRY_COLLAPSE", action: "ARCANE",
    motion: "Construct unequal translucent arcane planes, impossible perspective wedges, and floating prism seams around the fixed target without readable runes. Countable hits rotate or fold different depth planes, never the camera. F08 aligns the geometry; F09 collapses it through itself into a central dimensional fault and large viewer-facing prism fragments.",
    material: "amethyst prism glass, indigo spatial membranes, magenta edge light, and pale ivory arcane seams",
    paletteLow: "amethyst, indigo, muted magenta, pale ivory", paletteHigh: "imperial violet, black-indigo, white arcane light, royal magenta, sparse cyan",
  },
  {
    heroClass: "MAGE", candidate: 4, slug: "mage-cosmic-orbital", grammar: "COSMIC_ORBITAL_CATACLYSM", action: "COSMIC",
    motion: "Create orbital mass through curved gravity membranes, star-metal fragments, and luminous nodes revolving in several depths around the fixed center; do not draw space scenery or a literal planet. F08 forces the orbits into alignment; F09 produces a centered gravitational cataclysm with enormous cropped orbital plates and a deep readable void.",
    material: "violet cosmic glass, midnight gravity membranes, cyan star-metal edges, and white-gold singular contact light",
    paletteLow: "indigo, violet, cyan, pale starlight", paletteHigh: "black cosmic void, white-gold core, imperial violet, celestial cyan, deep crimson accents",
  },
  {
    heroClass: "CLERIC", candidate: 0, slug: "cleric-dawn-revelation", grammar: "DAWN_REVELATION_BEAM", action: "REVELATION",
    motion: "Form a revelation from layered ivory light membranes and sunburst facets that open from behind, before, above, and below the fixed target. Avoid a simple horizontal beam or generic circle. Countable hits are separate light-pressure disclosures. F08 converges them; F09 reveals a vast faceted dawn fault through every depth without scenery or text.",
    material: "ivory-gold light glass, pale rose dawn membranes, translucent blue-white facets, and restrained amber halos",
    paletteLow: "ivory, pale gold, dawn rose, light blue", paletteHigh: "white-gold, solar amber, celestial blue, imperial rose, sparse violet",
  },
  {
    heroClass: "CLERIC", candidate: 1, slug: "cleric-celestial-verdict", grammar: "CELESTIAL_VERDICT_DESCENT", action: "VERDICT",
    motion: "Suspend a massive abstract verdict weight above and around the fixed center using layered light slabs and balanced pressure arcs, never a literal hammer or hand. F02-F07 compress the space vertically while the camera stays fixed. F08 is exactly one decisive descent; F09 is only its consequence, a full-screen column fault and outward holy glass shock layers.",
    material: "white-gold judgment slabs, deep blue sacred glass, amber gravity arcs, and pale violet consequence shards",
    paletteLow: "ivory, muted gold, cobalt, pale amber", paletteHigh: "white-gold, royal blue, solar amber, imperial violet, black-gold seams",
  },
  {
    heroClass: "CLERIC", candidate: 2, slug: "cleric-exorcism-seal", grammar: "EXORCISM_SEAL_CONSUMPTION", action: "EXORCISE",
    motion: "Assemble an abstract exorcism seal from unequal luminous brackets, cleansing chains of light, and dark contaminant membranes around the fixed target, with no letters or religious symbols. Each hit removes a separate dark layer. F08 locks the final seal; F09 consumes the remaining darkness into a white-violet central fault and ejects purified prism fragments.",
    material: "pale violet cleansing glass, white-gold seal brackets, indigo contaminant membranes, and cyan purification sparks",
    paletteLow: "pale violet, ivory, muted gold, indigo", paletteHigh: "white purification light, imperial violet, black-indigo, royal gold, celestial cyan",
  },
  {
    heroClass: "CLERIC", candidate: 3, slug: "cleric-sacred-flame", grammar: "SACRED_FLAME_ASCENSION", action: "SACRED_FLAME",
    motion: "Grow sacred fire upward and outward from several depth layers around the fixed center as clean flameglass ribbons and luminous purification petals, never a generic fireball or background inferno. Countable hits are distinct ascending flame beats. F08 condenses them into a radiant core; F09 opens a towering full-screen white-gold flame vault with separated red-orange edges.",
    material: "white-gold flameglass, pale orange purification ribbons, crimson edge petals, and transparent blue-white heat membranes",
    paletteLow: "warm ivory, pale orange, gold, restrained crimson", paletteHigh: "white-gold, solar orange, deep crimson, celestial blue, royal violet accents",
  },
  {
    heroClass: "CLERIC", candidate: 4, slug: "cleric-angelic-host", grammar: "ANGELIC_HOST_CONVERGENCE", action: "HOST",
    motion: "Represent a heavenly host only through many independent wing-like pressure facets, spear-like rays, and vast luminous formation planes; never draw angels, faces, bodies, feathers as literal objects, or a battlefield. Countable hits arrive from distinct depth ranks. F08 converges the ranks; F09 sends an immense holy formation through the viewer with a fixed radiant center.",
    material: "ivory wing-pressure facets, blue-white formation glass, gold spear-rays, and violet celestial shadow membranes",
    paletteLow: "ivory, light blue, muted gold, pale violet", paletteHigh: "white-gold, celestial cobalt, imperial violet, solar amber, sparse crimson",
  },
  {
    heroClass: "PALADIN", candidate: 0, slug: "paladin-consecrated-rift", grammar: "CONSECRATED_CROSS_RIFT", action: "HOLY_RIFT",
    motion: "Build intersecting holy pressure planes and unequal blade-like light facets around the fixed center without drawing a sword or character. Countable hits form distinct sever planes. F08 aligns them into a disciplined central cross-rift; F09 splits the surrounding depth into large white-gold and blue-steel consequences without a generic X icon or flat slash.",
    material: "white-gold pressure blades, blue-steel sacred glass, pale cyan rift membranes, and restrained crimson contact sparks",
    paletteLow: "ivory, muted gold, blue steel, pale cyan", paletteHigh: "white-gold, royal blue, celestial cyan, imperial violet, sparse crimson",
  },
  {
    heroClass: "PALADIN", candidate: 1, slug: "paladin-aegis-citadel", grammar: "AEGIS_CITADEL_IMPACT", action: "AEGIS",
    motion: "Create defense as several curved translucent aegis plates and buttress-like pressure ribs layered in depth around the fixed target, never a literal shield, wall, or castle. Each hit is a separate compression beat. F08 drives all plates inward; F09 converts stored defense into a viewer-facing full-screen citadel shock and separated armored light facets.",
    material: "golden armored glass, cobalt aegis membranes, ivory buttress ribs, and amber pressure sparks",
    paletteLow: "muted gold, cobalt, ivory, bronze", paletteHigh: "white-gold, royal cobalt, black bronze, celestial cyan, imperial violet",
  },
  {
    heroClass: "PALADIN", candidate: 2, slug: "paladin-judgment-gravity", grammar: "JUDGMENT_HAMMER_GRAVITY", action: "GRAVITY_SMITE",
    motion: "Express a hammer blow only as an immense compressed gravity mass, vertical light column, and concentric armored shock plates above and around the fixed center; never draw a hammer or hand. F08 is exactly one decisive gravity contact. F09-F12 are only its consequences: crushed space, viewer-facing stoneglass fragments, and a lingering royal fault.",
    material: "bronze-gold gravity slabs, white impact columns, dark earth glass, and cobalt sacred pressure rings",
    paletteLow: "bronze, ivory, earth brown, muted blue", paletteHigh: "white-gold, black bronze, royal blue, molten amber, imperial violet",
  },
  {
    heroClass: "PALADIN", candidate: 3, slug: "paladin-aurora-vow", grammar: "AURORA_VOW_WAVE", action: "AURORA",
    motion: "Compose dawn from layered aurora membranes, golden horizon arcs, and luminous oath facets expanding through depth around the fixed target; no landscape, sun disc, or side-scrolling wave. Countable hits are separate dawn-pressure crests. F08 folds the crests inward; F09 releases a vast viewer-facing aurora vault with stable center and readable material layers.",
    material: "golden dawn glass, rose-white aurora ribbons, celestial blue membranes, and ivory oath facets",
    paletteLow: "pale gold, dawn rose, ivory, light blue", paletteHigh: "solar gold, white, imperial rose, royal blue, celestial violet",
  },
  {
    heroClass: "PALADIN", candidate: 4, slug: "paladin-sovereign-oath", grammar: "SOVEREIGN_OATH_CRUSADE", action: "OATH",
    motion: "Manifest an oath as royal clasp facets, regimented pressure standards, and successive armored light ranks converging through near, middle, and far depth. Never draw knights, banners with text, weapons, or a marching battlefield. Countable hits are distinct formation impacts. F08 unites the oath ranks; F09 turns them into a sovereign full-screen breakthrough toward the viewer without camera motion.",
    material: "royal gold clasp facets, ivory armored light ranks, deep blue oath membranes, and crimson-violet consequence edges",
    paletteLow: "gold, ivory, cobalt, restrained crimson", paletteHigh: "white-gold, royal cobalt, imperial violet, deep crimson, celestial cyan",
  },
];

function hitInstruction(skill) {
  if (skill.hits === 1) {
    return "Deliver exactly one decisive authored contact at F08, with F09-F12 reading only as its consequence; do not add a second attack.";
  }
  return `Honor the ${skill.hits}-hit identity with exactly ${skill.hits} distinct readable pressure beats across F03-F09; simultaneous paired beats are allowed only when visually separable, and no frame may be duplicated.`;
}

function buildManifest(family) {
  const classSkills = payload.skills
    .filter((skill) => skill.heroClass === family.heroClass && skill.candidate === family.candidate)
    .sort((left, right) => left.level - right.level);
  if (classSkills.length !== 20) throw new Error(`${family.slug} expected 20 skills, received ${classSkills.length}.`);
  const skills = classSkills.map((skill, index) => {
    const stage = index + 1;
    const finalTier = stage === 20
      ? "This stage-20 final skill must visibly exceed stage 19 with a third full-screen consequence layer, stronger material separation, larger foreground fragments, a more authoritative peak silhouette, and a longer clean residual without becoming a whiteout."
      : `Power stage ${stage} of 20; increase authored silhouette scale, depth count, foreground fragment size, material richness, and residual duration naturally with this stage.`;
    const palette = stage >= 16 ? family.paletteHigh : family.paletteLow;
    return {
      catalogId: skill.id,
      level: skill.level,
      name: skill.name,
      generationPrompt: `Create a completely new interpretation of ‘${skill.name}’ using ${family.grammar}. Unique stage blueprint: ${stageBlueprints[index]}, built from ${family.material}. ${hitInstruction(skill)} ${finalTier} Palette: ${palette}. Fill the combat viewport through multiple natural depth layers and intentional edge cropping. Keep the structural target, brightest contact core, and family-specific convergence point fixed at x=50%. Do not render the skill name, a character, an enemy, or UI text.`,
    };
  });
  return {
    schemaVersion: 1,
    templateOnly: false,
    scope: `${family.heroClass} candidate ${family.candidate + 1} full reinvention: twenty original ${family.grammar} animations`,
    sourcePolicy: {
      existingVfxIgnored: true,
      identityInputs: ["catalogId", "Korean skill name", "level", "power stage", "hit count"],
    },
    gateway: "http://127.0.0.1:4317",
    settings,
    commonPrompt: `${sharedOpening} ${classOpenings[family.heroClass]} Use ${family.grammar}: ${family.motion} ${sharedEnding}`,
    negativePrompt,
    skills,
  };
}

for (const family of families) {
  const output = resolve(scriptDir, `${family.slug}-sprite-prompts.json`);
  await writeFile(output, `${JSON.stringify(buildManifest(family), null, 2)}\n`, "utf8");
  process.stdout.write(`${family.heroClass}\t${family.slug}\t20\t${output}\n`);
}
