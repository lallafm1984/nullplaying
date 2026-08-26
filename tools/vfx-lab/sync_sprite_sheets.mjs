#!/usr/bin/env node

import { execFile } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { promisify } from "node:util";
import { resolve } from "node:path";

const jobsManifestInput = process.env.SPRITE_JOBS_MANIFEST;
if (!jobsManifestInput) {
  throw new Error("Set SPRITE_JOBS_MANIFEST to the job manifest created by queue_sprite_sheets.mjs.");
}

const jobsManifestPath = resolve(jobsManifestInput);
let manifest = JSON.parse(await readFile(jobsManifestPath, "utf8"));
if (!Array.isArray(manifest.jobs) || manifest.jobs.length === 0) {
  throw new Error("The job manifest does not contain any jobs.");
}

const expected = manifest.expected ?? {
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
const outputDir = resolve(process.env.SPRITE_OUTPUT_DIR || manifest.outputDir || "tools/vfx-lab/custom-assets/sprite-batch");
const baseUrl = process.env.FRAMESMITH_URL || manifest.gateway || "http://127.0.0.1:4317";
const watch = process.env.SPRITE_WATCH === "1";
const pollIntervalMs = Math.max(5_000, Number.parseInt(process.env.SPRITE_POLL_MS || "20000", 10));
const previousStatuses = new Map();
const synced = new Set();
const execFileAsync = promisify(execFile);
const cellNormalizer = resolve("tools/vfx-lab/normalize_sprite_sheet_cells.py");

await mkdir(outputDir, { recursive: true });

function pngMetadata(bytes) {
  const signature = [137, 80, 78, 71, 13, 10, 26, 10];
  if (bytes.length < 26 || !signature.every((value, index) => bytes[index] === value)) {
    throw new Error("FrameSmith result is not a PNG file.");
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return {
    width: view.getUint32(16),
    height: view.getUint32(20),
    bitDepth: bytes[24],
    colorType: bytes[25],
  };
}

function assertPngContract(catalogId, metadata) {
  if (metadata.width !== expected.sheetWidth || metadata.height !== expected.sheetHeight) {
    throw new Error(`${catalogId} is ${metadata.width}x${metadata.height}, expected ${expected.sheetWidth}x${expected.sheetHeight}.`);
  }
  if (metadata.bitDepth !== 8 || metadata.colorType !== 6) {
    throw new Error(`${catalogId} must be an 8-bit RGBA PNG (color type 6), received bit depth ${metadata.bitDepth}, color type ${metadata.colorType}.`);
  }
}

async function normalizeCells(catalogId, destination) {
  try {
    const { stdout } = await execFileAsync("python3", [cellNormalizer, destination]);
    process.stdout.write(`${JSON.stringify({ catalogId, normalization: stdout.trim() })}\n`);
  } catch (error) {
    throw new Error(`${catalogId} cell normalization failed: ${error.message}`);
  }
}

for (const jobSpec of manifest.jobs) {
  try {
    const bytes = new Uint8Array(await readFile(resolve(outputDir, `${jobSpec.catalogId}.png`)));
    assertPngContract(jobSpec.catalogId, pngMetadata(bytes));
    synced.add(jobSpec.catalogId);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
}

async function syncCompleted(jobSpec, job) {
  if (synced.has(jobSpec.catalogId)) return;
  if (
    job.mode !== expected.mode
    || job.frameWidth !== expected.frameWidth
    || job.frameHeight !== expected.frameHeight
    || job.frameCount !== expected.frameCount
    || job.columns !== expected.columns
    || job.rows !== expected.rows
    || job.fps !== expected.fps
    || job.loop !== expected.loop
  ) {
    throw new Error(`${jobSpec.catalogId} has unexpected sprite settings.`);
  }
  const response = await fetch(`${baseUrl}${job.resultUrl}`);
  if (!response.ok) throw new Error(`${jobSpec.catalogId} result download failed (${response.status}).`);
  const bytes = new Uint8Array(await response.arrayBuffer());
  const metadata = pngMetadata(bytes);
  assertPngContract(jobSpec.catalogId, metadata);
  const destination = resolve(outputDir, `${jobSpec.catalogId}.png`);
  await writeFile(destination, bytes);
  await normalizeCells(jobSpec.catalogId, destination);
  const normalizedBytes = new Uint8Array(await readFile(destination));
  assertPngContract(jobSpec.catalogId, pngMetadata(normalizedBytes));
  synced.add(jobSpec.catalogId);
  process.stdout.write(`${JSON.stringify({ catalogId: jobSpec.catalogId, status: "synced", ...metadata, rgba: true })}\n`);
}

async function poll() {
  // Quality-review manifests can gain catalogIds while a long FIFO generation is
  // already running. Reload the manifest without touching the gateway so the
  // watcher follows newly appended jobs instead of silently stopping at its
  // startup snapshot.
  manifest = JSON.parse(await readFile(jobsManifestPath, "utf8"));
  if (!Array.isArray(manifest.jobs) || manifest.jobs.length === 0) {
    throw new Error("The job manifest does not contain any jobs.");
  }
  const counts = { queued: 0, running: 0, completed: 0, failed: 0, cancelled: 0 };
  for (const jobSpec of manifest.jobs) {
    const response = await fetch(`${baseUrl}/api/jobs/${encodeURIComponent(jobSpec.jobId)}`);
    if (!response.ok) throw new Error(`${jobSpec.catalogId} status failed (${response.status}).`);
    const { job } = await response.json();
    counts[job.status] = (counts[job.status] || 0) + 1;
    if (previousStatuses.get(jobSpec.catalogId) !== job.status) {
      previousStatuses.set(jobSpec.catalogId, job.status);
      process.stdout.write(`${JSON.stringify({
        catalogId: jobSpec.catalogId,
        name: jobSpec.name,
        status: job.status,
        progress: job.progress,
      })}\n`);
    }
    if (job.status === "completed") await syncCompleted(jobSpec, job);
  }
  process.stdout.write(`${JSON.stringify({ summary: counts, synced: synced.size, total: manifest.jobs.length, outputDir })}\n`);
  return counts;
}

do {
  const counts = await poll();
  const pending = counts.queued + counts.running;
  if (!watch || pending === 0) {
    if (counts.failed || counts.cancelled) process.exitCode = 1;
    break;
  }
  await new Promise((resolvePoll) => setTimeout(resolvePoll, pollIntervalMs));
} while (true);
