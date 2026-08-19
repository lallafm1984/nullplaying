#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const skillsPath = resolve(scriptDir, "data/skills.json");
const payload = JSON.parse(await readFile(skillsPath, "utf8"));
const warriors = payload.skills.filter((skill) => skill.heroClass === "WARRIOR");

const settings = {
  type: "spritesheet",
  model: "gpt-5.6-sol",
  mode: "detailed",
  frameWidth: 361,
  frameHeight: 160,
  frameCount: 16,
  columns: 4,
  preset: "attack",
  fps: 16,
  loop: false,
  autoReview: true,
  qualityTarget: 90,
  maxPasses: 4,
  useReference: false,
};

const sharedOpening = "Use case: stylized-concept. Asset type: production 2D combat VFX overlay sprite sheet for AlarmQuest. Invent this family from scratch using only the Korean skill name, level, power stage, and requested hit rhythm. Completely ignore and do not imitate, trace, remix, or preserve any existing AlarmQuest VFX image, asset, animation, action tag, direction tag, palette, silhouette, or timing. Create exactly 16 chronological animation frames in a strict 4-column by 4-row, row-major sprite sheet. Every cell is a wide 361:160 combat viewport with the same fixed camera, ground line, scale, and central target at x=50%, y=55%. Detailed premium Korean fantasy action-RPG VFX, hand-painted high-resolution 2D rendering, smooth tonal shading, crisp silhouettes, rich material facets, controlled bloom, and clean alpha. Maximum visual impact and screen coverage take priority across the center; no clean channel is required for skill labels or damage numbers.";

const sharedEnding = "At F08-F10, combined near, middle, and far depth layers must occupy at least 90% of frame width and 82% of frame height, with natural foreground fragments intentionally cropped by all four edges. Fill the viewport through perspective, overlapping depth, and edge cropping, never by scaling or stretching one object. F01 is nearly transparent anticipation. F02-F04 gather force. F05-F07 accelerate. F08 is main contact. F09 is maximum impact. F10-F12 release fragments, shock fronts, and wakes through the viewer. F13-F14 retain a large readable afterimage. F15-F16 fade cleanly to transparency. Every frame must be distinct and continuous. No camera pan, camera zoom, camera rotation, or loop reset. Transparent RGBA background. F01 and F16 must be mostly transparent. Visual effect only: no warrior body and no enemy body. Do not render grid guides, frame labels, captions, borders, or padding.";

const negativePrompt = "pixel art, pixels, retro sprite, low resolution, nearest-neighbor edges, existing AlarmQuest VFX, copied game asset, traced reference, reused silhouette, character, warrior, human body, face, hands, legs, enemy, monster, fully rendered weapon object, background scenery, battlefield illustration, landscape, floor tile, user interface, HUD, health bar, text, Korean letters, English letters, numbers, logo, signature, watermark, frame number, caption, grid line, border, gutter, padding, opaque background, black background, white background, checkerboard background, tiny effect, miniature effect, small isolated center icon, empty peak corners, effect occupying less than eighty percent of peak frame, horizontal stretch, vertically stretched object, flat panorama smear, rubber distortion, warped proportions, mirrored butterfly silhouette, copy-pasted symmetry, single flat pressure strip, one giant scaled object, repeated identical frames, duplicated frame, missing frame, wrong frame order, inconsistent camera, inconsistent scale, unrelated secondary attack, excessive whiteout, excessive bloom, foggy silhouette, muddy colors, motion blur hiding material detail, jagged alpha, haloed alpha";

const familyDefinitions = [
  {
    candidate: 0,
    slug: "warrior-cleave",
    scope: "Warrior non-charge candidate 1 reinvention: twenty original full-screen sovereign cleaves",
    grammar: "SOVEREIGN_RIFT_CLEAVE",
    commonPrompt: `${sharedOpening} Use SOVEREIGN_RIFT_CLEAVE: do not move a slash from left to right. Compress two or more naturally curved steel-pressure edges around the center, snap them through the target plane from opposing depths, split the viewport into massive beveled sections, and send separated blade-pressure fragments toward the audience. Preserve each crescent's natural thickness. ${sharedEnding}`,
    motifs: [
      "a hairline steel seam that blooms into four ivory-edged plate splits",
      "a broad half-moon pressure arc opposed by a smaller counter-crescent",
      "three disciplined formation blades converging into one central rupture",
      "two thick steel planes shearing apart with bright internal grain",
      "a heavy great-blade pressure wedge followed by chipped iron wake plates",
      "a broken rotating ring of four naturally curved cleave sections",
      "three tiger-claw crescents with a compact ivory core and amber sparks",
      "a heroic double-chevron cleave with separate near and far edges",
      "an armored wheel of short curved edges that fractures into steel petals",
      "a fortress-wide center split with massive beveled wall fragments",
      "a swordmaster-thin first seam followed by a delayed monumental cross-rift",
      "two giant ridge-shaped pressure planes peeling away from the center",
      "three command-grade black-steel cleaves staggered through depth",
      "a grand-general chevron that breaks into six gold-edged armor plates",
      "a royal vertical seam intersected by two curved sovereign pressure edges",
      "paired overlord cleaves separating thick black-gold halves of the frame",
      "fang-like ivory crescents biting inward before bursting past the camera",
      "a war-king triple cleave with crimson wake ribbons and dense steel debris",
      "five disciplined horizon-pressure ranks collapsing into one central split",
      "the ultimate heaven-splitting lattice: white-hot central rift, four black-gold sovereign crescents, and three successive full-screen separation shocks",
    ],
  },
  {
    candidate: 1,
    slug: "warrior-crush",
    scope: "Warrior non-charge candidate 2 reinvention: twenty original skyfall crushing impacts",
    grammar: "TITAN_SKYFALL_CRUSH",
    commonPrompt: `${sharedOpening} Use TITAN_SKYFALL_CRUSH: a compact mass of pressure condenses high above the center, multiple naturally thick weight planes descend toward x=50%, y=55%, and a vertical ivory-hot impact column crushes through layered ground-pressure slabs. The attack must feel like immense weight, not a moving weapon or a flat top-to-bottom smear. Separate overhead mass, contact column, ground compression, and near-camera rubble into distinct depths. ${sharedEnding}`,
    motifs: [
      "a compact iron pressure block descending into a small amber ground dent",
      "layered armor-crack slabs collapsing around a hard white contact point",
      "a blunt steel cylinder of pressure surrounded by three broken weight rings",
      "a crest-shaped iron mass bursting into angular helmet-like fragments without showing a helmet",
      "a thick axe-wedge pressure shadow that collapses into a circular dust basin without a literal axe",
      "a giant square hammer-pressure slab with separate bronze shock plates",
      "a bone-breaking black-steel compression cage snapping at contact",
      "two gate-sized weight planes slamming into a bright stone compression bloom",
      "a siege-grade dark mass followed by a delayed second ground shock",
      "a fortress crushing column with thick gold fault borders and huge rubble",
      "a narrow descending core wrapped in four rotating iron pressure braces",
      "a colossal body-weight illusion made from stacked stone and steel pressure layers",
      "a dragon-bone faceted crush core with ivory ribs of pressure but no creature",
      "a cliff-sized fractured weight plane breaking before it reaches the viewer",
      "a royal gold compression pillar surrounded by disciplined crimson force bands",
      "an overlord black-gold mass that produces two consecutive ground-collapse fronts",
      "a mountain-collapse canopy folding inward around a white-hot vertical axis",
      "a war-king meteor-like steel pressure core with no fireball or sky background",
      "a continent-crushing stack of three separate beveled pressure slabs",
      "the ultimate peerless crush: a white-gold singular weight core, four black-steel compression vaults, and three full-screen ground shock tiers overtaking the camera",
    ],
  },
  {
    candidate: 3,
    slug: "warrior-earth",
    scope: "Warrior non-charge candidate 4 reinvention: twenty original tectonic rebirth attacks",
    grammar: "TECTONIC_REBIRTH",
    commonPrompt: `${sharedOpening} Use TECTONIC_REBIRTH: the entire ground-pressure plane first draws inward beneath the central target, then fractures into multiple naturally proportioned terrain plates, vertical stone-energy ribs, curved seismic rims, and near-camera rubble. This is neither a circular explosion nor a single enlarged rock. Show compression, fault opening, violent multi-depth uplift or collapse, then a deep residual scar. ${sharedEnding}`,
    motifs: [
      "a compact amber footfall crater expanding into four small beveled earth plates",
      "two low seismic rims and a broken ochre ground seam",
      "a dark central rock tooth surrounded by six ivory-lit fault lines",
      "three narrow stone pillars rising at different depths through a dusty pressure basin",
      "a wide ground compression bowl that snaps into thick angular plates",
      "a stepped fault line with separate near and far rock shelves",
      "two enormous earth plates peeling upward like pages with amber cores",
      "a deep-axis shatter sending black-brown strata and gold dust through every edge",
      "a crimson-lit fracture web collapsing inward before an ivory stone burst",
      "four layered strata ribs erupting from a deep central trench",
      "a canyon-length black seam with opposing gold-edged terrain plates",
      "a mountain-base pressure dome collapsing into a dense rubble vortex",
      "a battlefield-scale fractured ground lattice with disciplined dark-red energy veins",
      "a continental crust bloom of eight thick natural rock plates",
      "a giant basin implosion followed by two rising rings of broken strata",
      "a fortress-foundation collapse with black basalt blocks and royal-gold fault light",
      "a nation-splitting rupture of three staggered chasms and huge foreground slabs",
      "a ten-mountain collapse illusion built from separate domed pressure fronts",
      "a continent-wide central scar bordered by monumental obsidian strata",
      "the ultimate world fracture: white-hot deep core, black-gold tectonic crown, crimson mantle bands, giant plates through all edges, and three successive seismic fronts",
    ],
  },
  {
    candidate: 4,
    slug: "warrior-flurry",
    scope: "Warrior non-charge candidate 5 reinvention: twenty original centered battle-tempest combinations",
    grammar: "CENTERED_BATTLE_TEMPEST",
    commonPrompt: `${sharedOpening} Use CENTERED_BATTLE_TEMPEST: never translate repeated slashes from left to right. Build an authored combination around the central target using distinct curved pressure blades arriving from alternating depths and angles. Early strokes establish rhythm; F08 main cluster and F09 finisher create the densest centered storm; F10-F12 send separate blade fragments and afterimage ribbons past the camera. Every visible stroke must keep natural thickness and a clear individual trajectory while the combined attack fills the viewport. ${sharedEnding}`,
    motifs: [
      "a rough three-stroke triangle followed by a compact amber center burst",
      "three steel beats forming an offset crescent chain with a fourth echo",
      "five claw-like pressure strokes circling inward from alternating depths",
      "five battlefield arcs that form a broken ring before bursting outward",
      "five berserk crimson-steel strokes with unequal length and timing",
      "six rapid black-steel cuts forming a tightening spiral and one ivory finisher",
      "four blood-battle strokes arranged as two crosses with a delayed core rupture",
      "seven veteran arcs alternating gold and blue-steel pressure trails",
      "six frenzy cuts forming a jagged crown around the central target",
      "seven iron-blood strokes with thick crimson backs and sharp ivory edges",
      "seven unyielding blades forming a forward-folding pressure cage",
      "seven war-mad cuts orbiting at three depths before a vertical finisher",
      "six tyrant strokes: three broad black arcs and three fast crimson counter-arcs",
      "ten general-grade strokes grouped into three readable waves and a gold finisher",
      "eight overlord cuts forming two offset vortices that collide at the center",
      "twelve dragon-slaying fang strokes grouped into four depth waves without a dragon",
      "nine war-king arcs creating a dense black-gold storm with crimson afterimages",
      "eight world-spanning cuts folding inward from all four edges",
      "ten army-breaking pressure blades arriving in disciplined staggered ranks without soldiers",
      "the ultimate annihilating tempest: twelve distinct white-gold and black-steel strokes in four escalating waves, a crimson central pressure void, and three finisher cleaves overtaking the camera",
    ],
  },
];

function paletteFor(stage, family) {
  if (stage <= 5) {
    return family === "warrior-earth"
      ? "ochre stone, dark umber, restrained amber fault light, sparse ivory dust"
      : "cold steel, muted bronze, restrained amber sparks, sparse ivory contact light";
  }
  if (stage <= 10) {
    return family === "warrior-earth"
      ? "iron-brown strata, burnished gold faults, deep rust-red accents, ivory dust"
      : "polished steel, burnished gold, deep rust-red accents, ivory impact light";
  }
  if (stage <= 15) {
    return "black steel or basalt, royal gold, disciplined deep crimson, ivory-white impact light";
  }
  return "white-hot ivory, sovereign royal gold, black steel or obsidian, deep disciplined crimson, sparse orange sparks";
}

function buildManifest(family) {
  const skills = warriors
    .filter((skill) => skill.candidate === family.candidate)
    .sort((left, right) => left.level - right.level)
    .map((skill, index) => {
      const stage = index + 1;
      const hitInstruction = family.candidate === 4
        ? `Honor a ${skill.hits}-hit identity by grouping the visible pressure strokes into a readable ${skill.hits}-beat rhythm across F04-F12; simultaneous paired strokes are allowed when needed, but do not duplicate frames.`
        : "Deliver one decisive authored impact, with secondary shock fronts reading as consequences rather than extra attacks.";
      const finalTier = stage === 20
        ? "This is the stage-20 final skill and must visibly exceed stage 19 through a denser third shock front, more luxurious material detail, and a larger residual scar without becoming a whiteout."
        : `Power stage ${stage} of 20; make the silhouette, layer count, foreground fragment size, and residual duration grow naturally with this stage.`;
      return {
        catalogId: skill.id,
        level: skill.level,
        name: skill.name,
        generationPrompt: `Create a completely new interpretation of ‘${skill.name}’ using ${family.grammar}. Signature motif: ${family.motifs[index]}. ${hitInstruction} ${finalTier} Palette: ${paletteFor(stage, family.slug)}. Fill the combat viewport through multiple natural depth layers and intentional edge cropping. Do not render the skill name, a character, an enemy, or UI text.`,
      };
    });
  if (skills.length !== 20) throw new Error(`${family.slug} expected 20 skills, received ${skills.length}.`);
  return {
    schemaVersion: 1,
    templateOnly: false,
    scope: family.scope,
    sourcePolicy: {
      existingVfxIgnored: true,
      identityInputs: ["catalogId", "Korean skill name", "level", "power stage", "hit count for flurry only"],
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
