#!/usr/bin/env node

import { access, readdir, readFile } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const gateway = process.env.FRAMESMITH_URL || "http://127.0.0.1:4317";
const concurrency = Math.max(1, Number.parseInt(process.env.SPRITE_RUNTIME_CONCURRENCY || "16", 10));
const classPrefixes = new Set(["rogue", "ranger", "mage", "cleric", "paladin"]);

const exists = async (path) => {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
};

async function loadManifests() {
  const names = (await readdir(root)).filter((name) => name.endsWith("-sprite-jobs.json")).sort();
  const manifests = [];
  for (const name of names) {
    const path = join(root, name);
    const data = JSON.parse(await readFile(path, "utf8"));
    const jobs = Array.isArray(data.jobs) ? data.jobs : [];
    if (!jobs.length) continue;
    const className = String(jobs[0].catalogId || "").split("_")[0];
    if (!classPrefixes.has(className)) continue;
    manifests.push({
      name,
      path,
      className,
      kind: name.includes("quality-revision") ? "revision" : "base",
      outputDir: resolve(data.outputDir || join(root, "custom-assets", name.replace(/-sprite-jobs\.json$/, ""))),
      jobs,
    });
  }
  return manifests;
}

async function mapLimit(items, limit, fn) {
  const results = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const index = next;
      next += 1;
      results[index] = await fn(items[index], index);
    }
  });
  await Promise.all(workers);
  return results;
}

const healthResponse = await fetch(`${gateway}/api/health`);
if (!healthResponse.ok) throw new Error(`FrameSmith health failed (${healthResponse.status}).`);
const health = await healthResponse.json();
const manifests = await loadManifests();
const records = manifests.flatMap((manifest) => manifest.jobs.map((job) => ({ manifest, job })));
const live = await mapLimit(records, concurrency, async ({ manifest, job }) => {
  const response = await fetch(`${gateway}/api/jobs/${job.jobId}`);
  if (!response.ok) {
    return { manifest, job, reachable: false, status: `http-${response.status}`, png: false };
  }
  const payload = await response.json();
  const remote = payload.job || {};
  return {
    manifest,
    job,
    reachable: Boolean(remote.id),
    status: remote.status || "unknown",
    progress: remote.progress ?? null,
    updatedAt: remote.updatedAt ?? null,
    error: remote.error || "",
    png: await exists(join(manifest.outputDir, `${job.catalogId}.png`)),
  };
});

const statusKeys = ["queued", "running", "completed", "failed", "cancelled", "missing"];
function summarize(rows) {
  const statuses = Object.fromEntries(statusKeys.map((key) => [key, 0]));
  let pngs = 0;
  for (const row of rows) {
    const key = row.reachable ? row.status : "missing";
    statuses[key] = (statuses[key] || 0) + 1;
    if (row.png) pngs += 1;
  }
  return { jobs: rows.length, statuses, pngs };
}

const byClass = {};
for (const className of classPrefixes) {
  const baseRows = live.filter((row) => row.manifest.className === className && row.manifest.kind === "base");
  const revisionRows = live.filter((row) => row.manifest.className === className && row.manifest.kind === "revision");
  byClass[className] = { base: summarize(baseRows), revisions: summarize(revisionRows) };
}

const active = live.find((row) => row.job.jobId === health.activeJobId);
const activeUpdatedAgeSeconds = active?.updatedAt
  ? Math.max(0, Math.round((Date.now() - Date.parse(active.updatedAt)) / 1000))
  : null;
const jobIdCounts = new Map();
for (const row of live) jobIdCounts.set(row.job.jobId, (jobIdCounts.get(row.job.jobId) || 0) + 1);
const duplicateJobIds = [...jobIdCounts].filter(([, count]) => count > 1).map(([jobId, count]) => ({ jobId, count }));
const runningRows = live.filter((row) => row.status === "running");
const issues = live
  .filter((row) => !row.reachable || row.status === "failed" || row.status === "cancelled")
  .map((row) => ({
    manifest: row.manifest.name,
    catalogId: row.job.catalogId,
    jobId: row.job.jobId,
    status: row.status,
    error: row.error,
  }));
if (health.activeJobId && !active) {
  issues.push({ status: "untracked-active-job", jobId: health.activeJobId });
}
if (runningRows.length > 1) {
  issues.push({ status: "multiple-running-jobs", jobIds: runningRows.map((row) => row.job.jobId) });
}
if (active && activeUpdatedAgeSeconds !== null && activeUpdatedAgeSeconds > 600) {
  issues.push({
    status: "stale-active-job",
    catalogId: active.job.catalogId,
    jobId: active.job.jobId,
    updatedAt: active.updatedAt,
    ageSeconds: activeUpdatedAgeSeconds,
  });
}
for (const duplicate of duplicateJobIds) issues.push({ status: "duplicate-job-id", ...duplicate });

process.stdout.write(`${JSON.stringify({
  gateway,
  health: {
    ok: health.ok === true,
    ready: health.ready === true,
    isolatedJobs: health.isolatedJobs === true,
    activeJobId: health.activeJobId ?? null,
  },
  active: active ? {
    catalogId: active.job.catalogId,
    manifest: active.manifest.name,
    status: active.status,
    progress: active.progress,
    updatedAt: active.updatedAt,
    updatedAgeSeconds: activeUpdatedAgeSeconds,
  } : null,
  manifests: manifests.length,
  integrity: {
    uniqueJobIds: jobIdCounts.size,
    duplicateJobIds: duplicateJobIds.length,
    runningJobs: runningRows.length,
    activeJobTracked: health.activeJobId ? Boolean(active) : true,
  },
  total: summarize(live),
  byClass,
  issues,
}, null, 2)}\n`);

if (issues.length) process.exitCode = 1;
