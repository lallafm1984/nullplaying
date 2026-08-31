/* Isolated headless QA for the local visualization. Never opens the game. */
const { chromium } = require('/Users/lim/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.js');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const out = path.resolve(__dirname, '../../docs/audits/2026-08-31-stat-bonus-balance');

(async () => {
  const browser = await chromium.launch({ executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless:true,
    args:['--disable-background-networking','--disable-component-update','--no-first-run'] });
  const results=[];
  try {
    for (const width of [736,360]) for (const colorScheme of ['light','dark']) {
      const context=await browser.newContext({viewport:{width,height:1600},deviceScaleFactor:1,colorScheme,hasTouch:width===360});
      const requests=[],errors=[];
      await context.route('**/*',async route=>{
        const url=new URL(route.request().url());
        requests.push(url.href);
        if(url.protocol==='file:' || (url.protocol==='https:' && ['cdn.jsdelivr.net','unpkg.com'].includes(url.hostname))) await route.continue();
        else await route.abort();
      });
      const page=await context.newPage();
      page.on('pageerror',error=>errors.push(String(error)));
      await page.goto(pathToFileURL(path.join(out,'level100-caps-qa-wrapper.html')).href,{waitUntil:'load'});
      await page.addStyleTag({content:'body{padding:0}iframe{height:100vh}'});
      const frame=page.frames().find(f=>f.parentFrame());
      await frame.waitForSelector('#aq-level100-caps[data-rendered="true"]');
      await frame.waitForFunction(()=>document.querySelectorAll('.aq-series-line').length===24);
      await frame.waitForFunction(()=>[...document.querySelectorAll('.aq-chart')].every(svg=>Math.abs(svg.viewBox.baseVal.width-svg.getBoundingClientRect().width)<.1));
      const root=frame.locator('#aq-level100-caps');
      const layout=await frame.evaluate(()=>{
        const root=document.getElementById('aq-level100-caps');
        const charts=[...root.querySelectorAll('.aq-chart')];
        return {width:root.getBoundingClientRect().width,scrollWidth:document.documentElement.scrollWidth,innerWidth,
          charts:charts.map(svg=>{
            const box=svg.getBoundingClientRect(),frame=svg.querySelector('[data-chart-frame]').getBBox();
            const texts=[...svg.querySelectorAll('.tick text,.axis-title,.aq-cap-label')].map(n=>({text:n.textContent,b:n.getBoundingClientRect()}));
            const conflicts=[];
            for(let i=0;i<texts.length;i++)for(let j=i+1;j<texts.length;j++){
              const a=texts[i],b=texts[j];
              if(a.b.left<b.b.right+4&&a.b.right+4>b.b.left&&a.b.top<b.b.bottom+4&&a.b.bottom+4>b.b.top)conflicts.push([a.text,b.text]);
            }
            return {width:box.width,xTicks:svg.querySelectorAll('.aq-x-axis .tick').length,
              axisTitles:svg.querySelectorAll('text.axis-title[data-axis]').length,
              conflicts,clippedLabels:texts.filter(t=>t.b.left<box.left-.5||t.b.right>box.right+.5||t.b.top<box.top-.5||t.b.bottom>box.bottom+.5).map(t=>t.text),
              marksInFrame:[...svg.querySelectorAll('.aq-series-line,.aq-endpoint')].every(n=>{
                const b=n.getBBox();return b.x>=frame.x-.1&&b.y>=frame.y-.1&&b.x+b.width<=frame.x+frame.width+.1&&b.y+b.height<=frame.y+frame.height+.1;
              }),stroke:getComputedStyle(svg.querySelector('.aq-series-line')).stroke};
          })};
      });
      assert.equal(layout.width,width);
      assert(layout.scrollWidth<=width);
      assert.equal(layout.charts.length,4);
      for(const chart of layout.charts){
        assert.equal(chart.axisTitles,2);
        assert.deepEqual(chart.conflicts,[]);
        assert.deepEqual(chart.clippedLabels,[]);
        assert(chart.marksInFrame);
        if(chart.width<=360)assert(chart.xTicks<=4);
      }
      const overlay=frame.locator('[data-metric="hp"] [data-chart-hover-overlay]');
      const overlayBox=await overlay.boundingBox();
      const cursorX=overlayBox.x+overlayBox.width*.437;
      await page.mouse.move(cursorX,overlayBox.y+overlayBox.height*.42);
      await frame.locator('.aq-cross-tooltip:not([hidden])').waitFor({state:'visible'});
      assert.equal(await frame.locator('.aq-tip-row').count(),6);
      const hover=await frame.evaluate(()=>{
        const svg=document.querySelector('[data-metric="hp"] svg.aq-chart');
        const guide=svg.querySelector('[data-chart-hover-guide]');
        const x=+guide.getAttribute('x1');
        const data=JSON.parse(document.getElementById('aq-level100-cap-data').textContent);
        const plot=svg.querySelector('[data-chart-frame]').getBBox();
        const level=1+(x-plot.x-4)/(plot.width-8)*99;
        const values=data.series.WARRIOR;
        const left=Math.floor(level)-1,t=level-Math.floor(level);
        const expected=values[left][1]+(values[left+1][1]-values[left][1])*t;
        return {guideX:svg.getBoundingClientRect().left+x,markers:svg.querySelectorAll('[data-chart-hover-marker]').length,
          expectedText:expected.toFixed(2)+'시간',actualText:document.querySelector('.aq-tip-row').textContent,
          markerAlignment:[...svg.querySelectorAll('[data-chart-hover-marker]')].every(m=>Math.abs(+m.getAttribute('cx')-x)<.001)};
      });
      assert(Math.abs(hover.guideX-cursorX)<1,JSON.stringify({hover,cursorX,overlayBox}));
      assert(hover.markerAlignment&&hover.markers===6);
      assert(hover.actualText.includes(hover.expectedText));
      await overlay.click({position:{x:overlayBox.width*.437,y:overlayBox.height*.42}});
      await frame.getByRole('button',{name:'전사 곡선 표시',exact:true}).click();
      assert.equal(await frame.locator('.aq-tip-row').count(),5);
      assert.equal(await frame.locator('[data-series="WARRIOR"]').evaluateAll(ns=>ns.filter(n=>getComputedStyle(n).display==='none').length),4);
      assert.equal(await frame.locator('[data-chart-hover-marker="WARRIOR"]').count(),0);
      await frame.getByRole('button',{name:'전사 곡선 표시',exact:true}).click();
      assert.equal(await frame.locator('.aq-tip-row').count(),6);
      if(width===360){
        await overlay.tap({position:{x:overlayBox.width*.71,y:overlayBox.height*.4}});
        assert(await frame.locator('.aq-cross-tooltip').isVisible());
        const mp=frame.locator('[data-metric="mp"] [data-chart-hover-overlay]');
        await mp.tap();
        assert.equal(await frame.locator('[data-chart-hover-guide]').evaluateAll(ns=>ns.filter(n=>getComputedStyle(n).display!=='none').length),1);
        assert.equal(await frame.locator('[data-chart-hover-marker]').count(),6);
        await overlay.tap({position:{x:overlayBox.width*.71,y:overlayBox.height*.4}});
      }
      await root.screenshot({path:path.join(out,`level100-caps-${width}-${colorScheme}-interaction.png`)});
      // Return to an unpinned, uncluttered figure for visual inspection.
      await overlay.click({position:{x:overlayBox.width*.5,y:overlayBox.height*.5}});
      await page.mouse.move(2,2);
      await frame.locator('.aq-cross-tooltip').evaluate(n=>n.hidden=true);
      await root.screenshot({path:path.join(out,`level100-caps-${width}-${colorScheme}.png`)});
      assert.deepEqual(errors,[]);
      results.push({width,colorScheme,layout,hover,errors,requests,legendToggle:true,touchPin:width===360});
      await context.close();
    }
    fs.writeFileSync(path.join(out,'level100_caps_visual_qa.json'),JSON.stringify(results,null,2)+'\n');
    console.log(JSON.stringify(results,null,2));
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
