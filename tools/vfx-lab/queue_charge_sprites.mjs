#!/usr/bin/env node

import { readFile } from "node:fs/promises";

const manifestUrl = new URL("./charge-sprite-prompts.json", import.meta.url);
const manifest = JSON.parse(await readFile(manifestUrl, "utf8"));
const { useReference: _useReference, ...jobSettings } = manifest.settings;
const baseUrl = process.env.FRAMESMITH_URL || "http://127.0.0.1:4317";
const fromIndex = Math.max(0, Number.parseInt(process.env.CHARGE_FROM_INDEX || "1", 10));
const requestedLimit = Number.parseInt(process.env.CHARGE_JOB_LIMIT || "0", 10);
const candidates = manifest.skills.slice(fromIndex);
const skills = requestedLimit > 0 ? candidates.slice(0, requestedLimit) : candidates;

for (const skill of skills) {
  const response = await fetch(`${baseUrl}/api/jobs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      ...jobSettings,
      commonPrompt: manifest.commonPrompt,
      prompt: skill.generationPrompt,
      negativePrompt: manifest.negativePrompt,
      referenceDataUrl: null,
    }),
  });
  const payload = await response.json();
  if (!response.ok || !payload.job?.id) {
    throw new Error(`${skill.catalogId} queue failed (${response.status}): ${JSON.stringify(payload)}`);
  }
  process.stdout.write(`${JSON.stringify({
    catalogId: skill.catalogId,
    level: skill.level,
    name: skill.name,
    jobId: payload.job.id,
    status: payload.job.status,
  })}\n`);
}
