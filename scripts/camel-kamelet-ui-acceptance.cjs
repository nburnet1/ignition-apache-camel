/* Native Kamelets page smoke test; Playwright stays a separate development dependency. */
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const name = `ui-kamelet-${require('node:crypto').randomUUID().slice(0, 8)}`;
const fixture = (action) => execFileSync('python3', ['-c', `
import runpy,json,sys,urllib.error
m=runpy.run_path(sys.argv[1]);m['bootstrap']()
kind='com.noahburnette.ignition.apachecamel/camel-kamelet'
if sys.argv[3]=='cookies':
 print(json.dumps([{'name':c.name,'value':c.value,'url':m['BASE']} for c in m['COOKIES']]))
else:
 try:
  r=m['api']('GET','/data/api/v1/resources/find/'+kind+'/'+sys.argv[2])
 except urllib.error.HTTPError as e:
  if e.code!=404: raise
 else:
  if sys.argv[3]=='inspect': print(json.dumps(r))
  else: m['api']('POST','/data/api/v1/resources/delete/'+kind,[{'name':sys.argv[2],'signature':r['signature']}])
`, path.join(__dirname, 'camel-acceptance.py'), name, action], { encoding: 'utf8' });

(async () => {
  let browser;
  let page;
  const errors = [];
  try {
    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext();
    await context.addCookies(JSON.parse(fixture('cookies')));
    page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto('http://localhost:9288/app/services/apache-camel/library?tab=templates');
    await page.getByText('normalize-temperature-action', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Create Template', exact: true }).click();
    await page.locator('input[name="name"]').fill(name);
    const source = `apiVersion: camel.apache.org/v1
kind: Kamelet
metadata:
  name: imported-file-name
spec:
  definition:
    title: UI import
  template:
    from:
      uri: kamelet:source
      steps:
        - to: kamelet:sink
`;
    await page.locator('input[type="file"]').setInputFiles({ name: 'ui.kamelet.yaml', mimeType: 'text/yaml', buffer: Buffer.from(source) });
    await page.waitForFunction(expected => document.querySelector('textarea[name="config.yaml"]')?.value === expected, source);
    await page.locator('[role="combobox"][id$="-kamelet-language-input"]').click();
    await page.getByText('Jython DSL', { exact: true }).click();
    await page.locator('input[name="config.project"]').fill('CamelExamples');
    await page.locator('input[name="config.functionPath"]').fill('kamelet_templates.temperature');
    await page.getByRole('tab', { name: 'Resources', exact: true }).click();
    await page.getByRole('tabpanel', { name: 'Resources', exact: true }).waitFor({ state: 'visible' });
    await page.getByRole('tab', { name: 'Definition', exact: true }).click();
    assert.equal(await page.locator('input[name="config.functionPath"]').inputValue(), 'kamelet_templates.temperature');
    await page.locator('[role="combobox"][id$="-kamelet-language-input"]').click();
    // The grid has YAML cells too; select the dropdown option, not a row cell.
    await page.getByRole('option', { name: 'YAML', exact: true }).click();
    assert.equal(await page.getByRole('textbox', { name: 'Kamelet YAML *', exact: true }).inputValue(), source);
    await page.getByRole('button', { name: 'Create Template', exact: true }).last().click();
    await page.getByText(name, { exact: true }).waitFor({ timeout: 15000 });
    const saved = JSON.parse(fixture('inspect'));
    assert.equal(saved.config.language, 'YAML');
    assert.equal(saved.config.yaml, source);
    assert.equal(saved.config.functionPath, 'kamelet_templates.temperature');
    assert(saved.data.includes('config.kamelet.yaml'));
    assert.deepEqual(errors, []);
    console.log('Verified Kamelets page: YAML file import, YAML/Jython draft retention, tabs and native resource save.');
  } catch (error) {
    if (page) console.error((await page.locator('body').innerText()).slice(-5000));
    throw error;
  } finally {
    try { if (browser) await browser.close(); }
    finally { fixture('cleanup'); }
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
