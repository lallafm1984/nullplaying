#!/usr/bin/env node

import { copyFile, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";

const root = dirname(fileURLToPath(import.meta.url));
const gateway = process.env.FRAMESMITH_URL || "http://127.0.0.1:4317";
const apply = process.env.SPRITE_RESUME_APPLY === "1";
const snapshotPath = resolve(
  process.env.SPRITE_PAUSE_SNAPSHOT || join(root, "paused-future-class-jobs.json"),
);
const snapshot = JSON.parse(await readFile(snapshotPath, "utf8"));
const pausedJobs = snapshot.pausedJobs || [];
const groups = new Map();

for (const paused of pausedJobs) {
  const response = await fetch(`${gateway}/api/jobs/${encodeURIComponent(paused.jobId)}`);
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`Unable to verify ${paused.catalogId} (${response.status}).`);
  if (payload.job?.status !== "cancelled") continue;
  if (!groups.has(paused.manifestPath)) groups.set(paused.manifestPath, []);
  groups.get(paused.manifestPath).push(paused);
}

process.stdout.write(`${JSON.stringify({
  mode: apply ? "apply" : "dry-run",
  snapshot: snapshotPath,
  cancelledJobsToResume: [...groups.values()].reduce((sum, jobs) => sum + jobs.length, 0),
  manifests: groups.size,
})}\n`);
if (!apply) process.exit(0);

const stamp = new Date().toISOString().replace(/[:.]/g, "-");
for (const [manifestPath, jobs] of groups) {
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  const cancelledIds = new Set(jobs.map((job) => job.jobId));
  const backupPath = `${manifestPath}.before-resume-${stamp}`;
  await copyFile(manifestPath, backupPath);
  manifest.jobs = (manifest.jobs || []).filter((job) => !cancelledIds.has(job.jobId));
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");

  await new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(process.execPath, [join(root, "queue_sprite_sheets.mjs")], {
      cwd: root,
      stdio: "inherit",
      env: {
        ...process.env,
        FRAMESMITH_URL: gateway,
        SPRITE_PROMPT_MANIFEST: manifest.sourceManifest,
        SPRITE_JOBS_OUT: manifestPath,
        SPRITE_OUTPUT_DIR: manifest.outputDir,
      },
    });
    child.once("error", rejectPromise);
    child.once("exit", (code) => {
      if (code === 0) resolvePromise();
      else rejectPromise(new Error(`Requeue failed for ${manifestPath} with exit code ${code}.`));
    });
  });
  process.stdout.write(`${JSON.stringify({ manifest: manifestPath, resumed: jobs.length, backup: backupPath })}\n`);
}

snapshot.resumedAt = new Date().toISOString();
snapshot.resumedJobs = [...groups.values()].flat().map((job) => job.jobId);
await writeFile(snapshotPath, `${JSON.stringify(snapshot, null, 2)}\n`, "utf8");

