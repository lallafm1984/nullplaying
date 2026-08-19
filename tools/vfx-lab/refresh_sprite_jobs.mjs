#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const input = process.env.SPRITE_JOBS_MANIFEST;
if (!input) throw new Error("Set SPRITE_JOBS_MANIFEST to a FrameSmith job manifest.");

const manifestPath = resolve(input);
const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
if (!Array.isArray(manifest.jobs) || manifest.jobs.length === 0) {
  throw new Error("The job manifest does not contain any jobs.");
}

const baseUrl = process.env.FRAMESMITH_URL || manifest.gateway || "http://127.0.0.1:4317";
const counts = {};
for (const jobSpec of manifest.jobs) {
  const response = await fetch(`${baseUrl}/api/jobs/${encodeURIComponent(jobSpec.jobId)}`);
  if (!response.ok) throw new Error(`${jobSpec.catalogId} status failed (${response.status}).`);
  const { job } = await response.json();
  jobSpec.status = job.status;
  jobSpec.progress = job.progress;
  jobSpec.completedAt = job.completedAt ?? null;
  jobSpec.resultUrl = job.resultUrl ?? null;
  counts[job.status] = (counts[job.status] || 0) + 1;
}

manifest.refreshedAt = new Date().toISOString();
await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
process.stdout.write(`${JSON.stringify({ manifest: manifestPath, counts, total: manifest.jobs.length })}\n`);
