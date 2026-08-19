import { createRequire } from "node:module";
import { mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";

const require = createRequire(import.meta.url);
const sharp = require("sharp");

const FRAME_WIDTH = 361;
const FRAME_HEIGHT = 160;
const COLUMNS = 4;
const ROWS = 4;
const SHEET_WIDTH = FRAME_WIDTH * COLUMNS;
const SHEET_HEIGHT = FRAME_HEIGHT * ROWS;

const jobs = [
  {
    catalogId: "warrior_t02_c03",
    source: "tools/vfx-lab/custom-assets/warrior-t02-shield-break-late-impact-revision/warrior_t02_c03.png",
    output: "tools/vfx-lab/custom-assets/warrior-impact-alpha-cleanup/warrior_t02_c03.png",
    // F01 is only a misleading right-side ghost. F02-F07 keep the shield rim
    // on the far left and clear the wake drawn in front of it. F08 onward
    // already has the correct left-side wake for a rightward ram.
    clearFrames: [1],
    clearRightOf: [
      [2, 34],
      [3, 34],
      [4, 36],
      [5, 39],
      [6, 39],
      [7, 42],
    ],
    bottomRects: [
      [8, 270, 153, 24, 5],
      [9, 256, 144, 25, 14],
      [10, 283, 144, 25, 14],
      [11, 249, 144, 25, 14],
      [12, 221, 144, 27, 14],
      [12, 352, 150, 9, 8],
    ],
  },
  {
    catalogId: "warrior_t05_c02",
    source: "tools/vfx-lab/custom-assets/warrior-t05-axe-slam-explosion-connected-revision/warrior_t05_c02.png",
    output: "tools/vfx-lab/custom-assets/warrior-impact-alpha-cleanup/warrior_t05_c02.png",
    clearFrames: [],
    clearRightOf: [],
    bottomRects: [
      [5, 303, 149, 50, 9],
      [6, 257, 149, 99, 9],
      [7, 252, 148, 83, 10],
      [8, 273, 150, 35, 8],
      [9, 210, 143, 65, 15],
      [10, 212, 143, 47, 15],
      [11, 47, 143, 23, 15],
      [11, 103, 143, 24, 15],
      [11, 217, 143, 34, 15],
      [11, 276, 145, 13, 13],
      [12, 74, 143, 19, 15],
      [12, 222, 143, 29, 15],
    ],
  },
];

function frameOrigin(frameNumber) {
  const frameIndex = frameNumber - 1;
  return {
    x: (frameIndex % COLUMNS) * FRAME_WIDTH,
    y: Math.floor(frameIndex / COLUMNS) * FRAME_HEIGHT,
  };
}

function clearPixel(data, x, y) {
  const offset = (y * SHEET_WIDTH + x) * 4;
  const changed = data[offset] !== 0 || data[offset + 1] !== 0 || data[offset + 2] !== 0 || data[offset + 3] !== 0;
  data[offset] = 0;
  data[offset + 1] = 0;
  data[offset + 2] = 0;
  data[offset + 3] = 0;
  return changed ? 1 : 0;
}

function clearRect(data, frameNumber, localX, localY, width, height) {
  const origin = frameOrigin(frameNumber);
  let changedPixels = 0;
  const maxX = Math.min(FRAME_WIDTH, localX + width);
  const maxY = Math.min(FRAME_HEIGHT, localY + height);
  for (let y = Math.max(0, localY); y < maxY; y += 1) {
    for (let x = Math.max(0, localX); x < maxX; x += 1) {
      changedPixels += clearPixel(data, origin.x + x, origin.y + y);
    }
  }
  return changedPixels;
}

async function clean(job) {
  const source = resolve(job.source);
  const output = resolve(job.output);
  const { data, info } = await sharp(source).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  if (info.width !== SHEET_WIDTH || info.height !== SHEET_HEIGHT || info.channels !== 4) {
    throw new Error(`Unexpected sprite contract for ${source}: ${info.width}x${info.height} channels=${info.channels}`);
  }

  let changedPixels = 0;
  const changes = [];
  for (const frameNumber of job.clearFrames) {
    const count = clearRect(data, frameNumber, 0, 0, FRAME_WIDTH, FRAME_HEIGHT);
    changedPixels += count;
    changes.push({ frame: frameNumber, type: "clear-frame", changedPixels: count });
  }
  for (const [frameNumber, localX] of job.clearRightOf) {
    const count = clearRect(data, frameNumber, localX, 0, FRAME_WIDTH - localX, FRAME_HEIGHT);
    changedPixels += count;
    changes.push({ frame: frameNumber, type: "clear-right-of", localX, changedPixels: count });
  }
  for (const [frameNumber, localX, localY, width, height] of job.bottomRects) {
    const count = clearRect(data, frameNumber, localX, localY, width, height);
    changedPixels += count;
    changes.push({ frame: frameNumber, type: "clear-bottom-rect", rect: [localX, localY, width, height], changedPixels: count });
  }

  await mkdir(dirname(output), { recursive: true });
  await sharp(data, { raw: { width: SHEET_WIDTH, height: SHEET_HEIGHT, channels: 4 } })
    .png({ compressionLevel: 9, adaptiveFiltering: true })
    .toFile(output);

  return { catalogId: job.catalogId, source, output, changedPixels, changes };
}

const results = [];
for (const job of jobs) results.push(await clean(job));
process.stdout.write(`${JSON.stringify({ contract: { width: SHEET_WIDTH, height: SHEET_HEIGHT, channels: 4 }, results }, null, 2)}\n`);
