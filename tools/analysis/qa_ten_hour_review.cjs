const {chromium}=require('/Users/lim/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.js');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const out=process.argv[2]?path.resolve(process.argv[2]):path.resolve(__dirname,'../../docs/audits/2026-08-31-stat-bonus-balance');
(async()=>{
  const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',args:['--disable-background-networking','--no-first-run']});
  const checks=[];
  try{
    for(const width of [736,360])for(const colorScheme of ['light','dark']){
      const context=await browser.newContext({viewport:{width,height:950},colorScheme});
      await context.route('**/*',route=>route.request().url().startsWith('file:')?route.continue():route.abort());
      const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(String(e)));
      await page.goto(pathToFileURL(path.join(out,'ten-hour-growth-qa.html')).href);
      await page.addStyleTag({content:'body{padding:0}iframe{height:100vh}'});
      const frame=page.frames().find(f=>f.parentFrame());
      await frame.locator('.data-inline-chart-stage').waitFor();
      await frame.locator('svg').first().waitFor();
      const root=frame.locator('div[id^="data-inline-"]').first();
      const data=await root.evaluate(host=>{
        const s=host.shadowRoot;return {width:host.getBoundingClientRect().width,
          overflow:document.documentElement.scrollWidth>innerWidth,
          paths:[...s.querySelectorAll('path')].filter(n=>(n.getAttribute('d')||'').length>100).length,
          text:s.textContent.slice(-700),buttons:[...s.querySelectorAll('button')].map(b=>b.getAttribute('aria-label')||b.textContent),
          charts:s.querySelectorAll('svg').length};
      });
      assert(!data.overflow);assert(data.paths>=3);
      await root.screenshot({path:path.join(out,`ten-hour-growth-${width}-${colorScheme}.png`)});
      const toggle=frame.getByRole('button',{name:'Toggle 기존 12시간',exact:true});
      await toggle.click();assert.equal(await toggle.getAttribute('aria-pressed'),'false');
      await toggle.click();assert.equal(await toggle.getAttribute('aria-pressed'),'true');
      const graph=frame.locator('svg').first();const graphBox=await graph.boundingBox();
      await graph.hover({position:{x:graphBox.width*.75,y:graphBox.height*.5}});
      await frame.locator('.chart-tooltip').first().waitFor({state:'visible'});
      await page.mouse.move(2,2);
      await frame.getByRole('button',{name:'View data source',exact:true}).click();
      await frame.getByText('Overview',{exact:true}).waitFor();
      await frame.getByText('Data preview',{exact:true}).click();
      await frame.locator('table').first().waitFor();
      const previewText=await frame.locator('table').first().innerText();
      assert(previewText.includes('20')&&previewText.includes('12시간'),previewText);
      await page.keyboard.press('Escape');
      assert.deepEqual(errors,[]);
      checks.push({width,colorScheme,...data,errors,sourcePreview:true,network:'blocked'});
      await context.close();
    }
    const context=await browser.newContext({viewport:{width:736,height:950}});
    await context.route('**/*',route=>route.request().url().startsWith('file:')?route.continue():route.abort());
    const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(String(e)));
    await page.goto(pathToFileURL(path.join(out,'ten-hour-sources-qa.html')).href);
    const frame=page.frames().find(f=>f.parentFrame());
    const root=frame.locator('div[id^="data-inline-"]').first();
    await root.locator('button').first().waitFor();
    await root.locator('button').first().click();
    await root.getByText('10시간 상한과 성장속도 격차',{exact:true}).waitFor();
    await root.getByRole('button',{name:'10시간 상한과 성장속도 격차',exact:true}).click();
    await root.getByRole('tab',{name:'Data preview',exact:true}).first().waitFor();
    await root.screenshot({path:path.join(out,'ten-hour-sources-expanded.png')});
    assert.deepEqual(errors,[]);checks.push({receipt:true,expanded:true,errors});
    await context.close();
    fs.writeFileSync(path.join(out,'ten_hour_visual_qa.json'),JSON.stringify(checks,null,2)+'\n');
    console.log(JSON.stringify(checks,null,2));
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
