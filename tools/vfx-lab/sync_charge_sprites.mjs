#!/usr/bin/env node

import { mkdir, readFile, writeFile } from "node:fs/promises";

const jobsUrl = new URL("./charge-sprite-jobs.json", import.meta.url);
const outputUrl = new URL("./custom-assets/warrior-charge/", import.meta.url);
const manifest = JSON.parse(await readFile(jobsUrl, "utf8"));
const baseUrl = process.env.FRAMESMITH_URL || manifest.gateway || "http://127.0.0.1:4317";
const watch = process.env.CHARGE_WATCH === "1";
const previousStatuses = new Map();
const synced = new Set();

await mkdir(outputUrl, { recursive: true });

function pngDimensions(bytes) {
  const signature = [137, 80, 78, 71, 13, 10, 26, 10];
  if (bytes.length < 24 || !signature.every((value, index) => bytes[index] === value)) {
    throw new Error("FrameSmith result is not a PNG file.");
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return { width: view.getUint32(16), height: view.getUint32(20) };
}

async function syncCompleted(jobSpec, job) {
  if (synced.has(jobSpec.catalogId)) return;
  if (job.mode !== "detailed" || job.frameCount !== 16 || job.columns !== 4 || job.rows !== 4) {
    throw new Error(`${jobSpec.catalogId} has unexpected sprite settings.`);
  }
  const response = await fetch(`${baseUrl}${job.resultUrl}`);
  if (!response.ok) throw new Error(`${jobSpec.catalogId} result download failed (${response.status}).`);
  const bytes = new Uint8Array(await response.arrayBuffer());
  const dimensions = pngDimensions(bytes);
  if (dimensions.width !== 1444 || dimensions.height !== 640) {
    throw new Error(`${jobSpec.catalogId} is ${dimensions.width}x${dimensions.height}, expected 1444x640.`);
  }
  await writeFile(new URL(`${jobSpec.catalogId}.png`, outputUrl), bytes);
  synced.add(jobSpec.catalogId);
  process.stdout.write(`${JSON.stringify({ catalogId: jobSpec.catalogId, status: "synced", ...dimensions })}\n`);
}

async function poll() {
  const counts = { queued: 0, running: 0, completed: 0, failed: 0, cancelled: 0 };
  for (const jobSpec of manifest.jobs) {
    const response = await fetch(`${baseUrl}/api/jobs/${encodeURIComponent(jobSpec.jobId)}`);
    if (!response.ok) throw new Error(`${jobSpec.catalogId} status failed (${response.status}).`);
    const { job } = await response.json();
    counts[job.status] = (counts[job.status] || 0) + 1;
    if (previousStatuses.get(jobSpec.catalogId) !== job.status) {
      previousStatuses.set(jobSpec.catalogId, job.status);
      process.stdout.write(`${JSON.stringify({ catalogId: jobSpec.catalogId, name: jobSpec.name, status: job.status, progress: job.progress })}\n`);
    }
    if (job.status === "completed") await syncCompleted(jobSpec, job);
  }
  process.stdout.write(`${JSON.stringify({ summary: counts, synced: synced.size, total: manifest.jobs.length })}\n`);
  return counts;
}

do {
  const counts = await poll();
  const pending = counts.queued + counts.running;
  if (!watch || pending === 0) {
    if (counts.failed || counts.cancelled) process.exitCode = 1;
    break;
  }
  await new Promise((resolve) => setTimeout(resolve, 20_000));
} while (true);
