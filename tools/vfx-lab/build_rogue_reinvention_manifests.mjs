#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const skillsPath = resolve(scriptDir, "data/skills.json");
const payload = JSON.parse(await readFile(skillsPath, "utf8"));
const rogues = payload.skills.filter((skill) => skill.heroClass === "ROGUE");

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

const sharedOpening = "Use case: stylized-concept. Asset type: production 2D combat VFX overlay sprite sheet for AlarmQuest. Invent this rogue family entirely from scratch using only the stable catalogId, Korean skill name, level, power stage, and hit count. Completely ignore and do not imitate, trace, remix, or preserve any existing AlarmQuest VFX image, asset, animation, action tag, direction tag, palette, silhouette, or timing. Create exactly 16 chronological animation frames in a strict 4-column by 4-row, row-major sprite sheet. Every cell is a wide 361:160 combat viewport with the same fixed camera, ground line, scale, and central target at x=50%, y=55%. Detailed premium Korean fantasy action-RPG VFX, hand-painted high-resolution 2D rendering, smooth tonal shading, razor-clean silhouettes, rich translucent materials, controlled bloom, and clean alpha. The animation must read as supernatural rogue speed and precision without drawing a rogue body, weapon-holding hands, or an enemy. Maximum centered impact density and natural screen coverage take priority; damage-number visibility does not matter.";

const sharedEnding = "At F08-F10, combined near, middle, and far depth layers must occupy at least 90% of frame width and 82% of frame height, with naturally proportioned foreground fragments intentionally cropped by all four edges. Fill the viewport through perspective, overlapping depth, and edge cropping, never by scaling or stretching one object. F01 is nearly transparent anticipation. F02-F04 establish the concealed mechanism. F05-F07 accelerate and tighten around the fixed target. F08 is main contact. F09 is maximum impact. F10-F12 release fragments, pressure wakes, or severed energy through the viewer. F13-F14 retain a large readable afterimage. F15-F16 fade cleanly to transparency. Every frame must be distinct and continuous. No camera pan, camera zoom, camera rotation, camera shake, lateral target motion, or loop reset. Transparent RGBA background. F01 and F16 must be mostly transparent. Visual effect only: no rogue body and no enemy body. Do not render grid guides, frame labels, captions, borders, or padding.";

const negativePrompt = "pixel art, pixels, retro sprite, low resolution, nearest-neighbor edges, existing AlarmQuest VFX, copied game asset, traced reference, reused silhouette, character, rogue, assassin body, human body, face, hands, legs, enemy, monster, gore, blood splatter, literal snake, literal spider, fully rendered weapon object, background scenery, battlefield illustration, landscape, floor tile, user interface, HUD, health bar, text, Korean letters, English letters, numbers, logo, signature, watermark, frame number, caption, grid line, border, gutter, padding, opaque background, black background, white background, checkerboard background, tiny effect, miniature effect, small isolated center icon, empty peak corners, effect occupying less than eighty percent of peak frame, horizontal stretch, vertically stretched object, flat panorama smear, rubber distortion, warped proportions, mirrored butterfly silhouette, copy-pasted symmetry, one giant scaled object, repeated identical frames, duplicated frame, missing frame, wrong frame order, inconsistent camera, inconsistent scale, unrelated secondary attack, left-to-right sprite translation, drifting target, moving vanishing point, camera shake, excessive whiteout, excessive bloom, foggy silhouette, muddy colors, motion blur hiding material detail, jagged alpha, haloed alpha, adjacent frame residue, next-frame image, previous-frame image, cross-cell continuation, bottom image band";

const familyDefinitions = [
  {
    candidate: 0,
    slug: "rogue-needle-crown",
    scope: "Rogue candidate 1 reinvention: twenty original centered phantom-needle combinations",
    grammar: "PHANTOM_NEEDLE_CROWN",
    commonPrompt: `${sharedOpening} Use PHANTOM_NEEDLE_CROWN: do not slide daggers or slash trails across the screen. Build a constellation of thin pressure needles and short crescent facets at different depths around the fixed center. Each beat briefly appears, punctures inward along its own perspective ray, and leaves a crisp phantom notch. F08-F09 collapse the accumulated constellation into a centered crown-shaped rupture; F10-F12 eject separate silver-black facets past the camera. Individual beats must remain countable while the combined crown fills the viewport. ${sharedEnding}`,
    motifs: [
      "three quick silver puncture rays forming a compact triangular crown",
      "four paired fang facets closing in as two staggered depth beats",
      "three disciplined ivory needle points with a delayed violet core notch",
      "four hidden knife-pressure petals orbiting once before snapping inward",
      "three crescent-shaped silver punctures drawing a broken moon crown",
      "four wind-fast cyan needles folding into a double-chevron rupture",
      "four cardinal black-steel puncture planes with ruby edge sparks",
      "two ghost-white crossing cuts that leave six faint phantom pinholes",
      "three blood-red fang facets biting into a silver central star without blood",
      "four storm-blue needle ranks collapsing from alternating depths",
      "five illusion needles appearing as separate translucent afterimages",
      "two white-night blade crescents dividing into five precise contact points",
      "three nightmare-black punctures framed by violet glass fractures",
      "four sky-piercing silver facets descending through a cobalt pressure crown",
      "five star-lit needle rays forming an asymmetrical celestial clasp",
      "two infinite mirrored-depth punctures multiplying into eight perspective echoes without copy-pasted symmetry",
      "three god-slaying obsidian needle planes with white-hot royal edges",
      "four end-time dagger-pressure constellations collapsing into a crimson void",
      "ten night-lotus punctures grouped into three readable waves and a final ivory crown",
      "two ultimate unseen-kill rays that open a vast black-silver crown and three successive phantom ruptures",
    ],
  },
  {
    candidate: 1,
    slug: "rogue-umbra-collapse",
    scope: "Rogue candidate 2 reinvention: twenty original fixed-center umbral ambush collapses",
    grammar: "UMBRAL_AFTERIMAGE_COLLAPSE",
    commonPrompt: `${sharedOpening} Use UMBRAL_AFTERIMAGE_COLLAPSE: never show a character dashing from left to right. Dark translucent planes peel out of empty space at the fixed center, vanish into three or more depths, and reappear as separate afterimage folds already surrounding the target. The target does not move. F08-F09 crush the shadow folds inward into one black-violet contact cavity; F10-F12 turn them inside out toward the viewer as broad natural ribbons and glass-like fragments. The sensation of ambush comes from impossible depth changes, not camera motion or a traveling silhouette. ${sharedEnding}`,
    motifs: [
      "three soft black shadow folds shearing around a narrow violet seam",
      "one silent indigo void opening behind the target and snapping shut in a single beat",
      "two displaced silver afterimages crossing through separate foreground depths",
      "three darkness apertures blooming above, below, and behind the fixed target",
      "one black-glass cross fold that inverts into four violet wake planes",
      "two moonless shadow shutters striking from near and far depth",
      "three pitch-black crescent folds edged with restrained ruby light",
      "one translucent afterimage tunnel collapsing straight through the viewer",
      "two moonlit indigo ambush planes appearing inside a pale circular absence",
      "three shadow doubles expressed only as offset pressure silhouettes, never bodies",
      "one abyssal aperture framed by six thin silver afterimage membranes",
      "two formless attack folds that erase and redraw the central depth plane",
      "three black-cross sever planes opening a deep violet cavity",
      "one void leap expressed as a sudden inversion of foreground and far-shadow sheets",
      "two night-dance vortices colliding without dancers or character silhouettes",
      "three shadow-king ambush planes with obsidian facets and imperial purple edges",
      "one abyssal phantom sever creating seven displaced black-glass echoes",
      "two moonless ambush cavities swallowing all ambient light around the center",
      "three world-shadow separation planes peeling the viewport into impossible depths",
      "the ultimate last dance of the void: one perfectly silent black singular fold, four imperial violet afterimage vaults, and three full-screen depth inversions",
    ],
  },
  {
    candidate: 2,
    slug: "rogue-venom-helix",
    scope: "Rogue candidate 3 reinvention: twenty original centered venom-helix blooms",
    grammar: "VENOM_HELIX_BLOOM",
    commonPrompt: `${sharedOpening} Use VENOM_HELIX_BLOOM: no literal snake, creature, liquid spray traveling sideways, or generic circular explosion. Grow two or more translucent toxic filaments around the fixed central axis in different depths. Each filament tightens as a helix, forms faceted venom crystals or corrosive membrane petals, and punctures inward on its own beat. F08-F09 rupture the helix into a centered acidic bloom; F10-F12 cast separate near-camera toxin glass, vapor ribbons, and corrosion rings outward while the core remains fixed. ${sharedEnding}`,
    motifs: [
      "two jade venom needles twisting around a small black-green core",
      "three emerald corrosion blisters splitting into angular toxic petals",
      "two paired amethyst-jade needles locking into a double helix",
      "two toxic fog membranes pierced by sharp chartreuse crystal points",
      "three liquid-glass venom ribbons coiling into a faceted central bloom",
      "four corrosion rings collapsing into a blackened jade rupture",
      "two deep-purple toxin needles wrapped in thin emerald lightning veins",
      "three fang-like poison crystal facets without a snake or creature",
      "four green-black venom blooms detonating in staggered depth layers",
      "two royal toxin helices linked by six small corrosive notches",
      "three black-venom membranes tearing into luminous jade glass",
      "two dragon-poison needles expressed as monumental faceted toxin prisms without a dragon",
      "two venom-king fangs of energy closing around a dark emerald nucleus",
      "three abyssal toxin filaments puncturing an amethyst corrosion veil",
      "four virulent blooms forming a dense chartreuse-black pressure flower",
      "two calamity-poison chains expressed as linked toxic crystal rings",
      "three world-tree venom needles with jade veins and ancient amber motes, no tree scenery",
      "four end-venom ruptures folding a black-green membrane through the viewer",
      "two all-poison penetration helices carrying many distinct colored toxin facets",
      "the ultimate poison-god annihilation: three sovereign jade-amethyst helices, a black acidic singular core, and three enormous corrosion blooms overtaking every edge",
    ],
  },
  {
    candidate: 3,
    slug: "rogue-snare-convergence",
    scope: "Rogue candidate 4 reinvention: twenty original silent snare convergences",
    grammar: "SILENT_SNARE_CONVERGENCE",
    commonPrompt: `${sharedOpening} Use SILENT_SNARE_CONVERGENCE: do not draw a literal floor trap, cage, character, or side-scrolling wire. Fine silver-black tension lines and blade-like anchor facets emerge independently from beyond all four edges and from near/far depth, always aimed at the fixed target. F02-F07 progressively reveal an asymmetric restraint geometry. F08-F09 all lines snap taut into a dense centered bind and sever lattice. F10-F12 recoil toward the viewer as separate natural curves, broken links, and metallic glints. Keep the central knot fixed while outer anchors vary. ${sharedEnding}`,
    motifs: [
      "three low silver tension arcs and four small black anchor facets",
      "four razor-wire pressure lines crossing into a compact diamond sever knot",
      "two heavy loop arcs snapping around an amber-steel central clasp",
      "three hidden blade anchors unfolding into an asymmetrical trap star",
      "four moon-silver binding threads forming a layered faceted cocoon",
      "two rotating loop planes tightening from foreground and background depth",
      "three obsidian chain-pressure arcs linked by red tension sparks",
      "four polished steel sight-lines dividing the viewport into taut triangular planes",
      "two execution loops closing into a precise crimson-black central knot",
      "three blade-prison lattices forming separate near, middle, and far cages without literal bars",
      "four silver-thread waves weaving a dense asymmetric capture mesh",
      "two chain sever formations snapping into six black-steel link fragments",
      "three shadow snares visible only as violet tension refractions",
      "four sky-and-earth net layers converging from every edge into a gold clasp",
      "two moonlight wire crescents drawing a luminous double restraint ring",
      "three black-thread prison membranes with obsidian anchor petals",
      "four fate-binding lines carrying distinct ivory runic-like glints without text",
      "two reaper-loop pressure planes framed by cold silver and deep wine red",
      "three causality sever lines splitting depth into nine taut black-glass facets",
      "the ultimate heaven-net conclusion: four sovereign silver-black snare layers, twelve asymmetric anchors, and three consecutive full-screen tension snaps",
    ],
  },
  {
    candidate: 4,
    slug: "rogue-heartseal-execution",
    scope: "Rogue candidate 5 reinvention: twenty original single-beat heartseal executions",
    grammar: "HEARTSEAL_EXECUTION",
    commonPrompt: `${sharedOpening} Use HEARTSEAL_EXECUTION: deliver exactly one authored execution impact without gore, anatomy, a literal heart, or a character. An abstract black-glass seal forms around the fixed center from several unequal geometric pressure segments. F02-F07 remove sound and ambient light while the seal contracts. F08 is the single precise sever or puncture. F09 is the full-screen consequence: a sharp ivory-crimson fault through layered shadow glass. F10-F12 release large separated fragments and a deep residual void; never add a second attack. ${sharedEnding}`,
    motifs: [
      "a narrow ivory fault cutting a small black-glass weak-point seal",
      "one silent dark throat-like aperture represented only by abstract pressure rings, no anatomy",
      "one crimson needle puncturing a faceted wine-black life seal without gore",
      "a nearly soundless obsidian execution seam with four fading silver corners",
      "one red weak-point star sealed inside three black glass plates, no blood",
      "a blind-angle strike expressed as a missing wedge in a violet pressure circle",
      "one fatal black-silver separation line splitting six disciplined facets",
      "a pale spirit needle piercing a deep indigo soul-like prism without a figure",
      "one midnight weak-point seal eclipsed by a thin moon-white fault",
      "a death-mark singular seam that fractures the entire shadow plane once",
      "one life-cutting ivory edge passing through layered black-red glass",
      "a silent execution seal collapsing before a single royal-purple puncture",
      "one fate sever line dividing a gold-threaded obsidian sigil without letters",
      "a reaper-like weak-point eclipse using only abstract dark crescents, no figure",
      "one spirit execution fault shattering a translucent violet core",
      "a single existence-sever plane deleting the middle depth layer into a black void",
      "one king-grade throat seal expressed as a royal crimson clasp without anatomy",
      "a perfect weak-point singularity surrounded by eight still black-glass facets",
      "one instant execution flash where all shadow layers align for a single frame",
      "the ultimate point of death: one microscopic white-red contact at exact center followed by three immense black-glass reality fractures filling every edge",
    ],
  },
];

function paletteFor(stage, family) {
  const high = stage >= 16;
  if (family === "rogue-needle-crown") {
    return high ? "white-hot silver, black steel, imperial violet, disciplined crimson, sparse cyan sparks" : "cold silver, blue steel, muted violet, restrained crimson accents, pale ivory contact light";
  }
  if (family === "rogue-umbra-collapse") {
    return high ? "absolute black glass, imperial violet, moon-white edges, deep wine crimson" : "ink black, indigo, muted violet, thin moon-silver edges";
  }
  if (family === "rogue-venom-helix") {
    return high ? "sovereign jade, acidic chartreuse, amethyst toxin glass, black-green void, ivory corrosion light" : "jade green, toxic teal, muted amethyst, black-green shadow, sparse amber motes";
  }
  if (family === "rogue-snare-convergence") {
    return high ? "white silver, obsidian wire, imperial violet, deep crimson tension light, royal gold glints" : "brushed silver, black steel, restrained indigo, sparse amber tension sparks";
  }
  return high ? "black glass, white-hot ivory, deep wine crimson, imperial violet, sparse royal gold" : "black glass, pale ivory, muted wine red, deep indigo, sparse silver dust";
}

function hitInstruction(skill) {
  if (skill.hits === 1) {
    return "Deliver exactly one decisive authored contact at F08, with F09-F12 reading only as its consequence; do not add a second attack.";
  }
  return `Honor the ${skill.hits}-hit identity with ${skill.hits} distinct readable pressure beats across F03-F09; simultaneous paired beats are allowed only when visually separable, and no frame may be duplicated.`;
}

function buildManifest(family) {
  const skills = rogues
    .filter((skill) => skill.candidate === family.candidate)
    .sort((left, right) => left.level - right.level)
    .map((skill, index) => {
      const stage = index + 1;
      const finalTier = stage === 20
        ? "This stage-20 final skill must visibly exceed stage 19 through a third full-screen consequence layer, richer material separation, larger foreground fragments, and a longer clean residual without becoming a whiteout."
        : `Power stage ${stage} of 20; increase the authored silhouette, layer count, foreground fragment size, and residual duration naturally with this stage.`;
      return {
        catalogId: skill.id,
        level: skill.level,
        name: skill.name,
        generationPrompt: `Create a completely new interpretation of ‘${skill.name}’ using ${family.grammar}. Signature motif: ${family.motifs[index]}. ${hitInstruction(skill)} ${finalTier} Palette: ${paletteFor(stage, family.slug)}. Fill the combat viewport through multiple natural depth layers and intentional edge cropping. Keep the structural target, brightest contact core, and family-specific convergence point fixed at x=50%. Do not render the skill name, a character, an enemy, or UI text.`,
      };
    });
  if (skills.length !== 20) throw new Error(`${family.slug} expected 20 skills, received ${skills.length}.`);
  if (family.motifs.length !== 20) throw new Error(`${family.slug} expected 20 motifs, received ${family.motifs.length}.`);
  return {
    schemaVersion: 1,
    templateOnly: false,
    scope: family.scope,
    sourcePolicy: {
      existingVfxIgnored: true,
      identityInputs: ["catalogId", "Korean skill name", "level", "power stage", "hit count"],
    },
    gateway: "http://127.0.0.1:4317",
    settings,
    commonPrompt: family.commonPrompt,
    negativePrompt,
    skills,
  };
}

for (const family of familyDefinitions) {
  const output = resolve(scriptDir, `${family.slug}-sprite-prompts.json`);
  await writeFile(output, `${JSON.stringify(buildManifest(family), null, 2)}\n`, "utf8");
  process.stdout.write(`${family.slug}\t20\t${output}\n`);
}
