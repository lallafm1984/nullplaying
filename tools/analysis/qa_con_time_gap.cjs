const fs=require('node:fs');const path=require('node:path');
let source=fs.readFileSync(path.join(__dirname,'qa_ten_hour_review.cjs'),'utf8');
for(const [a,b] of [
  ['ten-hour-growth','con-time-gap'],['ten-hour-sources','con-time-gap-sources'],
  ['ten_hour_visual_qa.json','con-time-gap-visual-qa.json'],
  ['Toggle 기존 12시간','Toggle 전사'],
  ["previewText.includes('20')&&previewText.includes('12시간')","previewText.includes('전사')&&previewText.includes('20')"],
  ['10시간 상한과 성장속도 격차','시간 특화와 성장 격차 비교'],
]){if(!source.includes(a))throw new Error('Missing seam: '+a);source=source.replaceAll(a,b);}
new Function('require','process','__dirname',source)(require,process,__dirname);
