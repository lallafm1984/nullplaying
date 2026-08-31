const {chromium}=require('/Users/lim/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.js');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const out=path.resolve(__dirname,'../../docs/audits/2026-09-01-progression-bonus-redesign/con-mental-5050');
(async()=>{
  const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',args:['--disable-background-networking','--no-first-run']});
  const checks=[];
  try {
    for(const width of [736,360])for(const colorScheme of ['light','dark']){
      const context=await browser.newContext({viewport:{width,height:950},colorScheme});
      await context.route('**/*',r=>r.request().url().startsWith('file:')?r.continue():r.abort());
      const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(String(e)));
      await page.goto(pathToFileURL(path.join(out,'con-mental-sources-qa.html')).href);
      const frame=page.frames().find(f=>f.parentFrame());
      const root=frame.locator('div[id^="data-inline-"]').first();
      await root.locator('button').first().waitFor();
      assert.equal(await root.locator('button').first().getAttribute('aria-expanded'),'false');
      await root.locator('button').first().click();
      await root.getByRole('button',{name:'CON·INT/WIS 50:50 검증 결과',exact:true}).click();
      await root.getByRole('tab',{name:'Data preview',exact:true}).first().click();
      const preview=await root.locator('table').first().innerText();
      assert(preview.includes('마법사')&&preview.includes('33.25'),preview);
      assert(!(await frame.evaluate(()=>document.documentElement.scrollWidth>innerWidth)));
      await root.screenshot({path:path.join(out,`con-mental-sources-${width}-${colorScheme}.png`)});
      assert.deepEqual(errors,[]);
      checks.push({width,colorScheme,initiallyCollapsed:true,dataPreview:true,noOverflow:true,errors,network:'blocked'});
      await context.close();
    }
    fs.writeFileSync(path.join(out,'sources-visual-qa.json'),JSON.stringify(checks,null,2)+'\n');
    console.log(JSON.stringify(checks));
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
