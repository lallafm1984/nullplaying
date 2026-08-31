// Reuse the already tested read-only isolated-browser QA with this study's labels.
const fs = require('node:fs');
const path = require('node:path');
const filename = path.join(__dirname, 'qa_ten_hour_review.cjs');
let source = fs.readFileSync(filename, 'utf8');
const replacements = [
  ['ten-hour-growth', 'mage35-growth'],
  ['ten-hour-sources', 'mage35-sources'],
  ['ten_hour_visual_qa.json', 'mage35-visual-qa.json'],
  ['Toggle 기존 12시간', 'Toggle 마법사 30%'],
  ["previewText.includes('12시간')", "previewText.includes('마법사')"],
  ['10시간 상한과 성장속도 격차', '스킬 사용과 성장 효율'],
];
for (const [before, after] of replacements) {
  if (!source.includes(before)) throw new Error(`QA seam missing: ${before}`);
  source = source.replaceAll(before, after);
}
new Function('require','process','__dirname',source)(require,process,__dirname);
