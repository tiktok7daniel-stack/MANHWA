const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1100, height: 1400 } });
  await p.goto('file://' + process.argv[2]);
  await p.waitForTimeout(400);
  await p.screenshot({ path: process.argv[3], fullPage: true });
  await b.close();
})();
