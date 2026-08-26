#!/usr/bin/env node

import { mkdir, readFile, writeFile } from "node:fs/promises";
import { basename, dirname, resolve } from "node:path";

const promptManifestInput = process.env.SPRITE_PROMPT_MANIFEST;
if (!promptManifestInput) {
  throw new Error("Set SPRITE_PROMPT_MANIFEST to a sprite prompt manifest JSON file.");
}

const promptManifestPath = resolve(promptManifestInput);
const promptManifest = JSON.parse(await readFile(promptManifestPath, "utf8"));
if (promptManifest.templateOnly) {
  throw new Error("Copy the template, set templateOnly to false, and replace every {{PLACEHOLDER}} before queueing.");
}

const generationStrategyOverride = process.env.SPRITE_GENERATION_STRATEGY?.trim();
if (generationStrategyOverride && !["sequential", "single-sheet"].includes(generationStrategyOverride)) {
  throw new Error("SPRITE_GENERATION_STRATEGY must be sequential or single-sheet.");
}
const settings = {
  generationStrategy: "single-sheet",
  ...(promptManifest.settings ?? {}),
  ...(generationStrategyOverride ? { generationStrategy: generationStrategyOverride } : {}),
};
const expectedContract = {
  mode: "detailed",
  frameWidth: 361,
  frameHeight: 160,
  frameCount: 16,
  columns: 4,
  rows: 4,
  sheetWidth: 1444,
  sheetHeight: 640,
  fps: 16,
  loop: false,
};

for (const [key, expected] of Object.entries(expectedContract)) {
  if (key === "rows" || key.startsWith("sheet")) continue;
  if (settings[key] !== expected) {
    throw new Error(`settings.${key} must be ${JSON.stringify(expected)}; received ${JSON.stringify(settings[key])}.`);
  }
}
if (settings.frameCount / settings.columns !== expectedContract.rows) {
  throw new Error("The 16-frame contract requires a 4-column by 4-row sheet.");
}
if (!promptManifest.commonPrompt?.trim() || !promptManifest.negativePrompt?.trim()) {
  throw new Error("commonPrompt and negativePrompt are required.");
}
if (!Array.isArray(promptManifest.skills) || promptManifest.skills.length === 0) {
  throw new Error("skills must contain at least one catalogId/name/generationPrompt entry.");
}

const placeholderPattern = /\{\{[^}]+\}\}/;
const promptText = JSON.stringify(promptManifest);
if (placeholderPattern.test(promptText)) {
  throw new Error("Replace every {{PLACEHOLDER}} before queueing.");
}

const jobsOutputPath = resolve(
  process.env.SPRITE_JOBS_OUT
    || promptManifest.jobsOutput
    || promptManifestPath.replace(/\.json$/i, ".jobs.json"),
);
const batchSlug = basename(promptManifestPath).replace(/(?:-sprite-prompts|-prompts)?\.json$/i, "") || "sprite-batch";
const outputDir = resolve(
  process.env.SPRITE_OUTPUT_DIR
    || promptManifest.outputDir
    || dirname(promptManifestPath),
  process.env.SPRITE_OUTPUT_DIR || promptManifest.outputDir ? "" : `custom-assets/${batchSlug}`,
);
const baseUrl = process.env.FRAMESMITH_URL || promptManifest.gateway || "http://127.0.0.1:4317";
const fromIndex = Math.max(0, Number.parseInt(process.env.SPRITE_FROM_INDEX || "0", 10));
const requestedLimit = Number.parseInt(process.env.SPRITE_JOB_LIMIT || "0", 10);
const dryRun = process.env.SPRITE_DRY_RUN === "1";
const candidates = promptManifest.skills.slice(fromIndex);
const skills = requestedLimit > 0 ? candidates.slice(0, requestedLimit) : candidates;

const uniqueCatalogIds = new Set();
for (const skill of promptManifest.skills) {
  if (!skill.catalogId?.trim() || !skill.name?.trim() || !skill.generationPrompt?.trim()) {
    throw new Error("Every skill requires catalogId, name, and generationPrompt.");
  }
  if (uniqueCatalogIds.has(skill.catalogId)) throw new Error(`Duplicate catalogId: ${skill.catalogId}`);
  uniqueCatalogIds.add(skill.catalogId);
}

async function referenceDataUrlFor(skill) {
  const input = skill.referencePath || promptManifest.referencePath;
  if (!input) return null;
  const referencePath = resolve(input);
  const bytes = await readFile(referencePath);
  const pngSignature = [137, 80, 78, 71, 13, 10, 26, 10];
  if (bytes.length < 24 || !pngSignature.every((value, index) => bytes[index] === value)) {
    throw new Error(`${skill.catalogId} referencePath must point to a PNG file.`);
  }
  return {
    referencePath,
    dataUrl: `data:image/png;base64,${bytes.toString("base64")}`,
  };
}

let jobsManifest = {
  schemaVersion: 1,
  sourceManifest: promptManifestPath,
  gateway: baseUrl,
  outputDir,
  expected: expectedContract,
  jobs: [],
};
try {
  const existing = JSON.parse(await readFile(jobsOutputPath, "utf8"));
  if (Array.isArray(existing.jobs)) jobsManifest = existing;
} catch (error) {
  if (error.code !== "ENOENT") throw error;
}

const existingCatalogIds = new Set(jobsManifest.jobs.map((job) => job.catalogId));
const queueCandidates = skills.filter((skill) => !existingCatalogIds.has(skill.catalogId));
process.stdout.write(`${JSON.stringify({
  mode: dryRun ? "dry-run" : "queue",
  sourceManifest: promptManifestPath,
  jobsOutput: jobsOutputPath,
  outputDir,
  selected: skills.length,
  alreadyQueued: skills.length - queueCandidates.length,
  toQueue: queueCandidates.length,
  expected: expectedContract,
  generationStrategy: settings.generationStrategy,
})}\n`);
if (dryRun) process.exit(0);

const healthResponse = await fetch(`${baseUrl}/api/health`);
if (!healthResponse.ok) throw new Error(`FrameSmith health check failed (${healthResponse.status}).`);
const health = await healthResponse.json();
process.stdout.write(`${JSON.stringify({
  gateway: baseUrl,
  health: "ok",
  activeJobId: health.activeJobId ?? null,
  note: health.activeJobId ? "Existing active job left untouched; new jobs will remain queued." : "Queue is idle.",
})}\n`);

const { useReference: _useReference, rows: _rows, ...jobSettings } = settings;
await mkdir(dirname(jobsOutputPath), { recursive: true });
for (const skill of queueCandidates) {
  const reference = await referenceDataUrlFor(skill);
  const response = await fetch(`${baseUrl}/api/jobs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      ...jobSettings,
      commonPrompt: skill.commonPrompt?.trim() || promptManifest.commonPrompt,
      prompt: skill.generationPrompt,
      negativePrompt: promptManifest.negativePrompt,
      referenceDataUrl: reference?.dataUrl ?? null,
    }),
  });
  const payload = await response.json();
  if (!response.ok || !payload.job?.id) {
    throw new Error(`${skill.catalogId} queue failed (${response.status}): ${JSON.stringify(payload)}`);
  }
  const queuedJob = {
    catalogId: skill.catalogId,
    level: skill.level ?? null,
    name: skill.name,
    jobId: payload.job.id,
    status: payload.job.status,
    referencePath: reference?.referencePath ?? null,
  };
  jobsManifest.jobs.push(queuedJob);
  await writeFile(jobsOutputPath, `${JSON.stringify(jobsManifest, null, 2)}\n`, "utf8");
  process.stdout.write(`${JSON.stringify(queuedJob)}\n`);
}
