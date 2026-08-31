// Reuse the isolated browser checks without touching the user's browser sessions.
const fs = require('node:fs');
const path = require('node:path');
(async () => {
  for (const kind of ['offline', 'skill']) {
    let source = fs.readFileSync(path.join(__dirname, 'qa_ten_hour_review.cjs'), 'utf8');
    for (const [before, after] of [
      ['ten-hour-growth', `level-bonus-${kind}`],
      ['ten-hour-sources', 'level-bonus-sources'],
      ['ten_hour_visual_qa.json', `level-bonus-${kind}-visual-qa.json`],
      ['Toggle 기존 12시간', 'Toggle 전사'],
      ["previewText.includes('20')&&previewText.includes('12시간')", "previewText.includes('전사')&&previewText.includes('1')"],
      ['10시간 상한과 성장속도 격차', '증가 공식과 레벨별 평균 혜택'],
      ['(async()=>{', 'return (async()=>{'],
    ]) {
      if (!source.includes(before)) throw new Error(`Missing QA seam: ${before}`);
      source = source.replaceAll(before, after);
    }
    await new Function('require', 'process', '__dirname', source)(require, process, __dirname);
    if (process.exitCode) break;
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
