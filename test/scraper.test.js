// Runs app/src/main/assets/scraper.js against test/mock-thread.html in headless Chromium.
// Usage: node test/scraper.test.js
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const assert = require('assert');

(async () => {
  const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/scraper.js'), 'utf8');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 400, height: 800 } });
  await page.goto('file://' + path.join(__dirname, 'mock-thread.html'));
  await page.evaluate(() => {
    window.__result = new Promise((resolve, reject) => {
      window.XTractBridge = {
        progress() {},
        done(token, json) { resolve({ token, posts: JSON.parse(json) }); },
        error(token, msg) { reject(new Error(msg)); },
      };
    });
  });
  await page.evaluate(script.replace('__TOKEN__', '7'));
  const { token, posts } = await page.evaluate(() => window.__result);
  await browser.close();

  const ids = posts.map(p => Number(p.url.split('/').pop()));
  assert.strictEqual(token, 7);
  assert.strictEqual(posts.length, 63, 'all 60 replies + 3 expanded, got ' + posts.length);
  assert.deepStrictEqual(ids.slice(0, 3), [1001, 1002, 1003], 'page order kept');
  assert.ok(ids.includes(1501) && ids.includes(1503), 'hidden replies expanded');
  assert.ok(!ids.includes(1999), 'ads skipped');
  assert.ok(!ids.some(id => id >= 1700 && id < 1800), '"Discover more" excluded');
  assert.ok(posts.every(p => !p.url.includes('?')), 'query strings stripped');
  const p4 = posts.find(p => p.url.endsWith('/1004'));
  assert.strictEqual(p4.quote, 'quoted words');
  assert.ok(p4.text.startsWith('Reply number 4') && p4.text.includes('🔥'), 'emoji kept: ' + p4.text);
  assert.strictEqual(p4.handle, '@user4');
  assert.strictEqual(p4.name, 'User 4');
  assert.strictEqual(p4.time, '2026-10-24T12:00:00.000Z');
  assert.strictEqual(posts.find(p => p.url.endsWith('/1007')).media, 1);
  console.log('scraper.js OK:', posts.length, 'posts');
})().catch(e => { console.error(e); process.exit(1); });
