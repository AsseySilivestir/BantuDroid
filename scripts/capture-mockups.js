const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1500, height: 2200 } });
  
  // Load the mockup HTML
  await page.goto('file:///home/z/my-project/scripts/bantudroid-mockup.html');
  await page.waitForTimeout(2000); // Wait for fonts to load
  
  // Full page screenshot
  await page.screenshot({ 
    path: '/home/z/my-project/download/bantudroid-all-screens.png',
    fullPage: true 
  });
  
  console.log('Full page screenshot saved!');
  
  // Now capture individual screens by scrolling to each
  const screenWrappers = await page.$$('.screen-wrapper');
  for (let i = 0; i < screenWrappers.length; i++) {
    const wrapper = screenWrappers[i];
    const phone = await wrapper.$('.phone');
    if (phone) {
      await phone.screenshot({ 
        path: `/home/z/my-project/download/bantudroid-screen-${i + 1}.png`
      });
      console.log(`Screen ${i + 1} captured!`);
    }
  }
  
  await browser.close();
  console.log('All screenshots done!');
})();
