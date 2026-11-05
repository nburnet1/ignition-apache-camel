/* Real Perspective session, not a DOM mock. Install Playwright separately.
 * PLAYWRIGHT_MODULE may be an absolute path to a temporary installation.
 */
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

(async () => {
    const browser = await chromium.launch({ headless: true });
    try {
        const page = await browser.newPage({ viewport: { width: 1440, height: 1400 } });
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.goto('http://localhost:9288/data/perspective/client/global');
        const component = index => page.locator(`[data-component-path="C.0:${index}"]`);
        const stats = component(2);
        await stats.filter({ hasText: /\d+ generated/ }).waitFor();
        assert(!await page.locator('.ia_qualityOverlay').count(), 'Perspective quality overlay present');
        const initial = Number((await stats.innerText()).match(/(\d+) generated/)[1]);
        await component(3).click();
        const options = page.locator('a.iaDropdownCommon_option[data-label^="WO-"]');
        await options.first().waitFor();
        const labels = await options.evaluateAll(elements => elements.map(e => e.getAttribute('data-label')));
        const order = labels[0].split(' | ')[0];
        await options.first().click();
        await page.getByRole('button', { name: 'Start selected work order', exact: true }).click();
        await component(6).filter({ hasText: new RegExp(`${order} started on press-01`) }).waitFor({ timeout: 10000 });
        await component(7).filter({ hasText: order }).waitFor();
        await component(8).filter({ hasText: /notification received at/ }).waitFor({ timeout: 10000 });
        // No page reload: a newly generated order must appear in the bound list.
        await page.waitForFunction(({ initial }) => {
            const text = document.querySelector('[data-component-path="C.0:2"]').textContent;
            return Number(text.match(/(\d+) generated/)[1]) > initial;
        }, { initial }, { timeout: 40000 });
        await component(3).click();
        await options.first().waitFor();
        const updated = await options.evaluateAll(elements => elements.map(e => e.getAttribute('data-label')));
        assert(updated.some(label => !labels.includes(label)), 'Dropdown did not acquire a new order');
        await page.keyboard.press('Escape');
        await page.getByRole('button', { name: 'Run all 64 pattern checks', exact: true }).click();
        await component(10).filter({ hasText: /Running behavioral/ }).waitFor();
        await component(10).filter({ hasText: /64\/64 passed/ }).waitFor({ timeout: 60000 });
        assert.deepEqual(errors, []);
        if (process.env.EIP_SCREENSHOT) await page.screenshot({ path: process.env.EIP_SCREENSHOT, fullPage: true });
        console.log('Browser verified: select/start, live machine table, native Perspective notification, new dropdown options without reload, and Run 64 checks.');
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
