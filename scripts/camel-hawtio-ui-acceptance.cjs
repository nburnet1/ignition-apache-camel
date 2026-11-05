/* External Hawtio 5.3.x + the local development proxy must already be running.
 * Playwright is a separate test dependency, never bundled into the module.
 */
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const password = process.env.CAMEL_HAWTIO_PROXY_PASSWORD;
assert(password, 'Set CAMEL_HAWTIO_PROXY_PASSWORD to the running local proxy password');
const name = `hawtio-ui-${require('node:crypto').randomUUID().slice(0, 8)}`;
const fixture = (action) => execFileSync('python3', ['-c', `
import runpy, sys
c = runpy.run_path(sys.argv[1])["client"]()
assert c.bootstrap()
name = sys.argv[2]
if sys.argv[3] == "create":
    c.create(name, """- route:
    id: browser-timer
    from:
      uri: timer:browser-fixture?period=100
      steps:
        - setBody:
            constant: test
""")
    c.wait("browser route", lambda: c.api("GET", c.MODULE + "/camel-routes").get(name) == "RUNNING")
elif sys.argv[3] == "state":
    print(c.api("GET", c.MODULE + "/camel-route-details?name=" + name)["routes"][0]["state"])
else:
    c.delete(name)
`, path.join(__dirname, 'camel-hawtio-acceptance.py'), name, action], { stdio: 'pipe' });

(async () => {
  let browser;
  try {
    fixture('create');
    browser = await chromium.launch({ headless: true, args: ['--renderer-process-limit=2'] });
    const page = await browser.newPage();
    await page.goto(process.env.HAWTIO_UI_URL || 'http://127.0.0.1:9390/hawtio/');
    await page.waitForFunction(() => document.querySelector('#connect-login-form-username')
      || [...document.querySelectorAll('button')].some(button => button.textContent.trim() === 'Add connection'));
    let remote = page;
    if (!await page.locator('#connect-login-form-username').count()) {
      await page.getByRole('button', { name: 'Add connection', exact: true }).click();
      for (const [id, value] of Object.entries({ name, host: process.env.HAWTIO_PROXY_HOST || '127.0.0.1', port: '9190', path: '/jolokia/' })) {
        await page.locator(`#connection-form-${id}`).fill(value);
      }
      // PatternFly's visible toggle overlays the input; keyboard interaction is reliable.
      if (await page.locator('#connection-form-scheme').isChecked()) await page.locator('#connection-form-scheme').press('Space');
      await page.getByRole('button', { name: 'Add', exact: true }).click();
      [remote] = await Promise.all([
        page.context().waitForEvent('page'),
        page.getByRole('button', { name: 'Connect', exact: true }).click(),
      ]);
    }
    await remote.locator('#connect-login-form-username').fill('hawtio');
    await remote.locator('#connect-login-form-password').fill(password);
    await remote.getByRole('button', { name: 'Log in', exact: true }).click();
    try { await remote.getByRole('link', { name: 'Camel', exact: true }).waitFor({ timeout: 30000 }); }
    catch (error) { console.error((await remote.locator('body').innerText()).slice(0, 6000)); throw error; }
    await remote.getByRole('link', { name: 'Camel', exact: true }).click();
    await remote.getByText(`integration-${name}`, { exact: true }).first().waitFor();
    await remote.getByText(`integration-${name}`, { exact: true }).first().click();
    const contextTree = remote.locator(`[id="org.apache.camel-CamelContexts-integration-${name}-folder"]`);
    if (await contextTree.getAttribute('aria-expanded') !== 'true') {
      await contextTree.locator('.pf-v6-c-tree-view__node-toggle').first().click();
    }
    await contextTree.getByText('routes', { exact: true }).click();
    const readonly = process.env.HAWTIO_SCOPE !== 'module';
    if (readonly) {
      await remote.locator('.react-flow__node').first().waitFor({ timeout: 20000 });
      assert.equal(await remote.getByText('Could not locate any XML for route.', { exact: true }).count(), 0);
    }
    await remote.getByText('Routes', { exact: true }).click();
    const row = remote.locator('#camel-routes-table tbody tr').filter({ hasText: 'browser-timer' });
    try { await row.getByRole('checkbox').check(); }
    catch (error) { console.error((await remote.locator('body').innerText()).slice(0, 6000)); throw error; }
    if (readonly) {
      assert(await remote.getByRole('button', { name: 'Stop', exact: true }).isDisabled());
      assert(await remote.getByRole('button', { name: 'Start', exact: true }).isDisabled());
      assert.equal(fixture('state').toString().trim(), 'RUNNING');
      console.log('External Hawtio browser verified: authentication, route diagrams/metrics and disabled mutations in JVM-readonly mode.');
      return;
    }
    await remote.getByRole('button', { name: 'Stop', exact: true }).click();
    await row.getByText('Stopped', { exact: true }).waitFor({ timeout: 20000 });
    assert.equal(fixture('state').toString().trim(), 'STOPPED');
    await remote.getByRole('button', { name: 'Start', exact: true }).click();
    await row.getByText('Started', { exact: true }).waitFor({ timeout: 20000 });
    assert.equal(fixture('state').toString().trim(), 'RUNNING');
    console.log('External Hawtio browser verified: authentication, scoped discovery, route metrics, stop/start and matching Ignition states.');
  } finally {
    try { if (browser) await browser.close(); }
    finally { fixture('delete'); }
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
