#!/usr/bin/env node

import { access, readdir, readFile, stat } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)));
const evidenceRoot = join(root, "evidence", "all-class-sprite-audits");
const auditScript = join(root, "audit_sprite_frames.py");
const watch = process.env.SPRITE_AUDIT_WATCH === "1";
const intervalMs = Math.max(2_000, Number.parseInt(process.env.SPRITE_AUDIT_INTERVAL_MS || "10000", 10));
const classPrefixes = new Set(["rogue", "ranger", "mage", "cleric", "paladin"]);

const exists = async (path) => {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
};

async function manifests() {
  const names = await readdir(root);
  const result = [];
  for (const name of names.filter((entry) => entry.endsWith("-sprite-jobs.json")).sort()) {
    const path = join(root, name);
    const manifest = JSON.parse(await readFile(path, "utf8"));
    const jobs = Array.isArray(manifest.jobs) ? manifest.jobs : [];
    if (!jobs.length) continue;
    const prefix = String(jobs[0].catalogId || "").split("_")[0];
    if (!classPrefixes.has(prefix)) continue;
    result.push({ path, name, manifest, jobs });
  }
  return result;
}

async function auditOnce() {
  const batches = await manifests();
  const auditScriptStat = await stat(auditScript);
  let pngs = 0;
  let current = 0;
  let created = 0;
  let failed = 0;
  for (const batch of batches) {
    const outputDir = resolve(batch.manifest.outputDir || join(root, "custom-assets", batch.name.replace(/-sprite-jobs\.json$/, "")));
    const batchSlug = basename(outputDir);
    for (const job of batch.jobs) {
      const png = join(outputDir, `${job.catalogId}.png`);
      if (!(await exists(png))) continue;
      pngs += 1;
      const output = join(evidenceRoot, batchSlug, job.catalogId);
      const report = join(output, "sprite-frame-audit.json");
      if (await exists(report)) {
        const [pngStat, reportStat] = await Promise.all([stat(png), stat(report)]);
        if (reportStat.mtimeMs >= Math.max(pngStat.mtimeMs, auditScriptStat.mtimeMs)) {
          current += 1;
          continue;
        }
      }
      const result = spawnSync("python3", [auditScript, "--output-dir", output, png], {
        cwd: root,
        encoding: "utf8",
      });
      if (result.status === 0) {
        created += 1;
        process.stdout.write(`${JSON.stringify({ catalogId: job.catalogId, status: "audited", report })}\n`);
      } else {
        failed += 1;
        process.stderr.write(`${JSON.stringify({ catalogId: job.catalogId, status: "audit-failed", error: result.stderr?.trim() || result.stdout?.trim() || `exit ${result.status}` })}\n`);
      }
    }
  }
  process.stdout.write(`${JSON.stringify({ batches: batches.length, pngs, current, created, failed, evidenceRoot })}\n`);
  if (failed) process.exitCode = 1;
}

do {
  await auditOnce();
  if (!watch) break;
  await new Promise((resolvePromise) => setTimeout(resolvePromise, intervalMs));
} while (true);
