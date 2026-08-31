// Same isolated-browser visual checks, applied to both final class comparisons.
const fs=require('node:fs');const path=require('node:path');
(async()=>{
  for(const kind of ['growth','sales']){
    let source=fs.readFileSync(path.join(__dirname,'qa_ten_hour_review.cjs'),'utf8');
    for(const [before,after] of [
      ['ten-hour-growth',`final-class-${kind}`],
      ['ten-hour-sources','final-class-sources'],
      ['ten_hour_visual_qa.json',`final-class-${kind}-visual-qa.json`],
      ['Toggle 기존 12시간','Toggle 전사'],
      ["previewText.includes('20')&&previewText.includes('12시간')","previewText.includes('전사')&&previewText.includes('6')"],
      ['10시간 상한과 성장속도 격차','100레벨 종합 혜택'],
      ['(async()=>{','return (async()=>{'],
    ]){
      if(!source.includes(before))throw new Error(`QA seam missing: ${before}`);
      source=source.replaceAll(before,after);
    }
    await new Function('require','process','__dirname',source)(require,process,__dirname);
    if(process.exitCode)break;
  }
})().catch(e=>{console.error(e);process.exitCode=1;});
