#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const lab = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(lab, "../..");
const catalogPath = path.join(root, "app/src/simple/java/com/alarmquest/engine/SkillCatalog.kt");
const outputPath = path.join(lab, "data/signature-skills.json");
const catalog = fs.readFileSync(catalogPath, "utf8");

const classOrder = ["WARRIOR", "ROGUE", "RANGER", "MAGE", "CLERIC", "PALADIN"];
const classFolders = {
  RANGER: "ranger-signature-t01-t20-intuitive-imagegen-refined-v1",
  MAGE: "mage-signature-t01-t20-intuitive-imagegen-refined-v1",
  CLERIC: "cleric-signature-t01-t20-intuitive-imagegen-refined-v1",
  PALADIN: "paladin-signature-t01-t20-intuitive-imagegen-refined-v1",
};
const warriorSources = {
  warrior_t01_c01: "warrior-t01-blade-slash-snap-impact-revision/warrior_t01_c01.png",
  warrior_t02_c03: "warrior-t02-steel-slice-attached-image/warrior_t02_c03.png",
  warrior_t03_c02: "warrior-t03-shatter-strike-attached-image/warrior_t03_c02.png",
  warrior_t04_c03: "warrior-t04-earth-cleave-slash-then-rift-imagegen-v1/warrior_t04_c03.png",
  warrior_t05_c02: "warrior-t05-cross-slash-horizontal-vertical-afterimage-imagegen-v1/warrior_t05_c02.png",
  warrior_t06_c05: "warrior-t06-storm-slash-six-gale-cascade-imagegen-v1/warrior_t06_c05.png",
  warrior_t14_c03: "warrior-t14-dragonslayer-attached-image/warrior_t14_c03.png",
  warrior_t15_c01: "warrior-t15-heaven-erasing-flash-black-matte-imagegen-refined-v3/warrior_t15_c01.png",
  warrior_t16_c05: "warrior-t16-boundless-void-domain-black-matte-imagegen-refined-v2/warrior_t16_c05.png",
  warrior_t17_c01: "warrior-t17-final-blow-falling-void-guillotine-imagegen-refined-v5/warrior_t17_c01.png",
  warrior_t18_c02: "warrior-t17-t20-style-cohesion-dark-painterly-imagegen-refined-v4/warrior_t18_c02.png",
  warrior_t19_c02: "warrior-t17-t20-style-cohesion-dark-painterly-imagegen-refined-v4/warrior_t19_c02.png",
  warrior_t20_c01: "warrior-t17-t20-style-cohesion-dark-painterly-imagegen-refined-v4/warrior_t20_c01.png",
};

function signatureRows(heroClass) {
  const marker = `HeroClass.${heroClass} to signatureRows(`;
  const start = catalog.indexOf(marker);
  if (start < 0) throw new Error(`Missing signature block for ${heroClass}`);
  const bodyStart = catalog.indexOf('"""', start) + 3;
  const bodyEnd = catalog.indexOf('"""', bodyStart);
  return catalog.slice(bodyStart, bodyEnd).trim().split(/\r?\n/).map((line) => {
    const [id, name, hits] = line.trim().split("|");
    if (!id || !name || !hits) throw new Error(`Invalid ${heroClass} signature row: ${line}`);
    return { id, name, hits: Number(hits) };
  });
}

function sourceFor(heroClass, id) {
  if (heroClass === "WARRIOR") {
    const relative = warriorSources[id] ?? `warrior-t07-t20-intuitive-skills-imagegen-v1/${id}.png`;
    return `custom-assets/${relative}`;
  }
  if (heroClass === "ROGUE") {
    return `custom-assets/rogue-finalized-vfx/${id}.png`;
  }
  return `custom-assets/${classFolders[heroClass]}/${id}.png`;
}

function pngContract(relativePath) {
  const data = fs.readFileSync(path.join(lab, relativePath));
  if (data.toString("hex", 0, 8) !== "89504e470d0a1a0a") {
    throw new Error(`Not a PNG: ${relativePath}`);
  }
  const width = data.readUInt32BE(16);
  const height = data.readUInt32BE(20);
  const bitDepth = data[24];
  const colorType = data[25];
  if (width !== 1444 || ![480, 640].includes(height) || bitDepth !== 8 || colorType !== 6) {
    throw new Error(`Invalid RGBA sheet ${relativePath}: ${width}x${height}, depth=${bitDepth}, type=${colorType}`);
  }
  return { columns: 4, rows: height / 160, frameCount: (height / 160) * 4 };
}

const skills = classOrder.flatMap((heroClass) => {
  const rows = signatureRows(heroClass);
  if (rows.length !== 20) throw new Error(`${heroClass} has ${rows.length} skills, expected 20`);
  return rows.map((skill, index) => {
    const tier = index + 1;
    const expectedPrefix = `${heroClass.toLowerCase()}_t${String(tier).padStart(2, "0")}_`;
    if (!skill.id.startsWith(expectedPrefix)) {
      throw new Error(`${skill.id} is not tier ${tier} for ${heroClass}`);
    }
    const source = sourceFor(heroClass, skill.id);
    const layout = pngContract(source);
    return {
      ...skill,
      heroClass,
      tier,
      unlockLevel: tier === 1 ? 1 : (tier - 1) * 5,
      source,
      ...layout,
      frameDurationMillis: 62.5,
      finalFrameHoldMillis: skill.id === "warrior_t01_c01" ? 0 : 100,
      fadeOutMillis: skill.id === "warrior_t01_c01" ? 0 : 250,
    };
  });
});

if (skills.length !== 120 || new Set(skills.map(({ id }) => id)).size !== 120) {
  throw new Error("Signature manifest must contain 120 unique catalog IDs");
}

fs.writeFileSync(outputPath, `${JSON.stringify({ schemaVersion: 1, skills }, null, 2)}\n`);
console.log(`signatureSkills=${skills.length}`);
console.log(`manifest=${path.relative(root, outputPath)}`);
