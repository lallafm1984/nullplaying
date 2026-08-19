#!/usr/bin/env node

import { access, readdir, readFile, writeFile } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const gateway = process.env.FRAMESMITH_URL || "http://127.0.0.1:4317";
const apply = process.env.SPRITE_PAUSE_APPLY === "1";
const targetClasses = new Set(
  (process.env.SPRITE_PAUSE_CLASSES || "ranger,mage,cleric,paladin")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean),
);
const snapshotPath = resolve(
  process.env.SPRITE_PAUSE_SNAPSHOT || join(root, "paused-future-class-jobs.json"),
);

async function exists(path) {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

async function getJson(url, options) {
  const response = await fetch(url, options);
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`${options?.method || "GET"} ${url} failed (${response.status}): ${JSON.stringify(payload)}`);
  return payload;
}

const health = await getJson(`${gateway}/api/health`);
const manifestNames = (await readdir(root))
  .filter((name) => name.endsWith("-sprite-jobs.json"))
  .sort();
const records = [];
for (const name of manifestNames) {
  const path = join(root, name);
  const manifest = JSON.parse(await readFile(path, "utf8"));
  for (const job of manifest.jobs || []) {
    const className = String(job.catalogId || "").split("_")[0];
    if (!targetClasses.has(className)) continue;
    const remote = await getJson(`${gateway}/api/jobs/${encodeURIComponent(job.jobId)}`);
    records.push({
      className,
      manifest: name,
      manifestPath: path,
      sourceManifest: manifest.sourceManifest,
      outputDir: manifest.outputDir,
      catalogId: job.catalogId,
      name: job.name,
      jobId: job.jobId,
      status: remote.job?.status || "unknown",
      createdAt: remote.job?.createdAt || "",
    });
  }
}

const statusCounts = records.reduce((counts, record) => {
  counts[record.status] = (counts[record.status] || 0) + 1;
  return counts;
}, {});
const queued = records
  .filter((record) => record.status === "queued" && record.jobId !== health.activeJobId)
  .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));

process.stdout.write(`${JSON.stringify({
  mode: apply ? "apply" : "dry-run",
  gateway,
  activeJobId: health.activeJobId ?? null,
  targetClasses: [...targetClasses],
  targetJobs: records.length,
  statuses: statusCounts,
  queuedToPause: queued.length,
  snapshot: snapshotPath,
})}\n`);
if (!apply) process.exit(0);

let snapshot = {
  schemaVersion: 1,
  gateway,
  targetClasses: [...targetClasses],
  createdAt: new Date().toISOString(),
  updatedAt: null,
  pausedJobs: [],
};
if (await exists(snapshotPath)) {
  snapshot = JSON.parse(await readFile(snapshotPath, "utf8"));
}
const existingIds = new Set((snapshot.pausedJobs || []).map((job) => job.jobId));
let cancelled = 0;
let skippedActive = 0;
let skippedChanged = 0;

for (const record of queued) {
  const current = await getJson(`${gateway}/api/jobs/${encodeURIComponent(record.jobId)}`);
  if (current.job?.status !== "queued") {
    skippedChanged += 1;
    continue;
  }
  const currentHealth = await getJson(`${gateway}/api/health`);
  if (currentHealth.activeJobId === record.jobId) {
    skippedActive += 1;
    continue;
  }
  const payload = await getJson(`${gateway}/api/jobs/${encodeURIComponent(record.jobId)}`, { method: "DELETE" });
  if (payload.job?.status !== "cancelled") {
    throw new Error(`${record.catalogId} did not enter cancelled state.`);
  }
  if (!existingIds.has(record.jobId)) {
    snapshot.pausedJobs.push({ ...record, pausedAt: new Date().toISOString() });
    existingIds.add(record.jobId);
  }
  cancelled += 1;
  if (cancelled % 25 === 0) {
    snapshot.updatedAt = new Date().toISOString();
    await writeFile(snapshotPath, `${JSON.stringify(snapshot, null, 2)}\n`, "utf8");
    process.stdout.write(`${JSON.stringify({ progress: cancelled, total: queued.length })}\n`);
  }
}

snapshot.updatedAt = new Date().toISOString();
await writeFile(snapshotPath, `${JSON.stringify(snapshot, null, 2)}\n`, "utf8");
process.stdout.write(`${JSON.stringify({
  status: "paused",
  cancelled,
  skippedActive,
  skippedChanged,
  pausedJobs: snapshot.pausedJobs.length,
  snapshot: snapshotPath,
})}\n`);

