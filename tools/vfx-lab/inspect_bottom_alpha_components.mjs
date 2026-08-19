import { createRequire } from "node:module";
import { resolve } from "node:path";

const require = createRequire(import.meta.url);
const sharp = require("sharp");

const FRAME_WIDTH = 361;
const FRAME_HEIGHT = 160;
const COLUMNS = 4;
const ROWS = 4;
const ALPHA_THRESHOLD = 8;
const BOTTOM_START = 132;

function indexOf(x, y) {
  return y * FRAME_WIDTH + x;
}

function componentsForFrame(frameData) {
  const visited = new Uint8Array(FRAME_WIDTH * FRAME_HEIGHT);
  const components = [];
  for (let y = 0; y < FRAME_HEIGHT; y += 1) {
    for (let x = 0; x < FRAME_WIDTH; x += 1) {
      const start = indexOf(x, y);
      if (visited[start] || frameData[start * 4 + 3] <= ALPHA_THRESHOLD) continue;
      visited[start] = 1;
      const queue = [start];
      let cursor = 0;
      let minX = x;
      let maxX = x;
      let minY = y;
      let maxY = y;
      let pixels = 0;
      let alphaSum = 0;
      while (cursor < queue.length) {
        const point = queue[cursor++];
        const px = point % FRAME_WIDTH;
        const py = Math.floor(point / FRAME_WIDTH);
        pixels += 1;
        alphaSum += frameData[point * 4 + 3];
        minX = Math.min(minX, px);
        maxX = Math.max(maxX, px);
        minY = Math.min(minY, py);
        maxY = Math.max(maxY, py);
        for (let dy = -1; dy <= 1; dy += 1) {
          for (let dx = -1; dx <= 1; dx += 1) {
            if (dx === 0 && dy === 0) continue;
            const nx = px + dx;
            const ny = py + dy;
            if (nx < 0 || nx >= FRAME_WIDTH || ny < 0 || ny >= FRAME_HEIGHT) continue;
            const next = indexOf(nx, ny);
            if (visited[next] || frameData[next * 4 + 3] <= ALPHA_THRESHOLD) continue;
            visited[next] = 1;
            queue.push(next);
          }
        }
      }
      components.push({
        pixels,
        meanAlpha: Number((alphaSum / pixels).toFixed(1)),
        bbox: [minX, minY, maxX, maxY],
        width: maxX - minX + 1,
        height: maxY - minY + 1,
        touchesBottomRegion: maxY >= BOTTOM_START,
      });
    }
  }
  return components;
}

async function inspect(source) {
  const absolute = resolve(source);
  const { data, info } = await sharp(absolute).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  if (info.width !== FRAME_WIDTH * COLUMNS || info.height !== FRAME_HEIGHT * ROWS || info.channels !== 4) {
    throw new Error(`Unexpected sprite contract for ${absolute}: ${info.width}x${info.height} channels=${info.channels}`);
  }
  const frames = [];
  for (let frameIndex = 0; frameIndex < COLUMNS * ROWS; frameIndex += 1) {
    const column = frameIndex % COLUMNS;
    const row = Math.floor(frameIndex / COLUMNS);
    const frameData = Buffer.alloc(FRAME_WIDTH * FRAME_HEIGHT * 4);
    for (let y = 0; y < FRAME_HEIGHT; y += 1) {
      const sourceStart = (((row * FRAME_HEIGHT + y) * info.width) + column * FRAME_WIDTH) * 4;
      data.copy(frameData, y * FRAME_WIDTH * 4, sourceStart, sourceStart + FRAME_WIDTH * 4);
    }
    const bottomComponents = componentsForFrame(frameData)
      .filter((component) => component.touchesBottomRegion && component.pixels >= 2)
      .sort((left, right) => right.pixels - left.pixels);
    frames.push({ frame: frameIndex + 1, bottomComponents });
  }
  return { source: absolute, alphaThreshold: ALPHA_THRESHOLD, bottomStart: BOTTOM_START, frames };
}

for (const source of process.argv.slice(2)) {
  process.stdout.write(`${JSON.stringify(await inspect(source))}\n`);
}
