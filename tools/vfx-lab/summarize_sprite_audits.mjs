#!/usr/bin/env node

import { readFile, readdir } from "node:fs/promises";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const evidenceRoot = resolve(process.env.SPRITE_AUDIT_ROOT || join(root, "evidence", "all-class-sprite-audits"));

async function reportsIn(directory) {
  const reports = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) reports.push(...await reportsIn(path));
    else if (entry.isFile() && entry.name === "sprite-frame-audit.json") reports.push(path);
  }
  return reports;
}

const reportPaths = (await reportsIn(evidenceRoot)).sort();
const rows = [];
for (const reportPath of reportPaths) {
  const payload = JSON.parse(await readFile(reportPath, "utf8"));
  const audit = Array.isArray(payload) ? payload[0] : payload;
  const frames = audit?.frames ?? [];
  const frame = (number) => frames.find((item) => item.frame === number) ?? {};
  const parts = relative(evidenceRoot, reportPath).split("/");
  rows.push({
    batch: parts[0] ?? "",
    catalogId: parts[1] ?? "",
    visualReviewPriority: Boolean(audit?.visualReviewPriority),
    signals: Object.entries(audit?.qaSignals ?? {}).filter(([, value]) => value).map(([key]) => key),
    metrics: {
      f01MeanAlphaPct: frame(1).meanAlphaPct ?? null,
      f08OccupancyPct: frame(8).alphaOccupancyPct ?? null,
      f09MeanAlphaPct: frame(9).meanAlphaPct ?? null,
      f09SymmetryPct: frame(9).leftRightAlphaSymmetryPct ?? null,
      f12MeanAlphaPct: frame(12).meanAlphaPct ?? null,
      f13MeanAlphaPct: frame(13).meanAlphaPct ?? null,
      f16MeanAlphaPct: frame(16).meanAlphaPct ?? null,
    },
    report: reportPath,
  });
}

const signalCounts = {};
for (const row of rows) {
  for (const signal of row.signals) signalCounts[signal] = (signalCounts[signal] || 0) + 1;
}

process.stdout.write(`${JSON.stringify({
  evidenceRoot,
  reports: rows.length,
  visualReviewPriority: rows.filter((row) => row.visualReviewPriority).length,
  signalCounts,
  rows,
}, null, 2)}\n`);
