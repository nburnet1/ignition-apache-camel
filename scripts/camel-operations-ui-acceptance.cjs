/* Read-only operations UI checks on 9288. Playwright is an external dev dependency. */
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

(async () => {
  let browser;
  let page;
  const errors = [];
  try {
    const cookies = JSON.parse(execFileSync('python3', ['-c', `
import runpy,json,sys
m=runpy.run_path(sys.argv[1]);m['bootstrap']()
print(json.dumps([{'name':c.name,'value':c.value,'url':m['BASE']} for c in m['COOKIES']]))
`, path.join(__dirname, 'camel-acceptance.py')], { encoding: 'utf8' }));
    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext();
    await context.addCookies(cookies);
    page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    const openDetails = async (url, name) => {
      await page.goto('http://localhost:9288/app/services/apache-camel/' + url);
      const row = page.locator(`[data-table-type="row"][data-row-id="${name}"]`);
      await row.getByRole('button', { name: 'show-more-button' }).click();
      await page.getByText('View Details', { exact: true }).click();
    };
    await openDetails('library?tab=templates', 'normalize-temperature-action');
    await page.getByText('Used by / change impact', { exact: true }).waitFor();
    await page.getByText('camel-example-kamelet-temperature', { exact: true }).first().waitFor();
    await page.getByText('Declared parameters', { exact: true }).waitFor();
    await openDetails('integrations', 'camel-example-kamelet-temperature');
    await page.getByRole('tab', { name: 'Diagnostics', exact: true }).click();
    await page.getByText('Recent error metadata', { exact: true }).waitFor();
    await page.getByRole('tab', { name: 'Resources', exact: true }).click();
    await page.getByText('Required resources', { exact: true }).waitFor();
    await page.getByText('normalize-temperature-action', { exact: true }).first().waitFor();

    assert.deepEqual(errors, []);
    console.log('Verified operations UI: Kamelet callers/parameters and route diagnostics/dependencies. No messages sent or configuration written.');
  } catch (error) {
    if (page) console.error((await page.locator('body').innerText()).slice(-6000));
    throw error;
  } finally { if (browser) await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
