/* Keep an actual Perspective page alive for targeted native component messages. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
    const browser = await chromium.launch({ headless: true });
    const watchdog = setTimeout(() => process.exit(2), 360000);
    try {
        const page = await browser.newPage();
        await page.goto('http://localhost:9288/data/perspective/client/global/component-benchmark');
        const session = page.locator('[data-component-path="C.0:0"]');
        await session.filter({ hasText: /^[a-f0-9-]{36}$/i }).waitFor({ timeout: 15000 });
        const sessionId = (await session.innerText()).trim();
        console.log(JSON.stringify({ sessionId }));
        await new Promise(resolve => { process.stdin.once('data', resolve); process.stdin.once('end', resolve); });
    } finally {
        clearTimeout(watchdog);
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
