// Takes the web screenshots from a keepIT seeded by seed-demo.sh. See README.md in this folder.
//
//   node scripts/screenshots/web-shots.cjs <base-url> <out-dir>
//
// Writes web-notes-dark.png, web-notes-light.png and web-editor-dim.png: a 1440x900 window at
// twice the density, so 2880x1800. finish.py puts them in the repo.
//
// It needs `npm ci` in web/ and that Playwright's Chromium (`npx playwright install chromium`
// there), the same as the browser smoke test.
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');

// Playwright is the web app's dev dependency, so it is loaded from there.
const { chromium } = createRequire(path.join(__dirname, '../../web/package.json'))('@playwright/test');

const [base, out] = process.argv.slice(2);
if (!base || !out) {
  console.error('usage: node web-shots.cjs <base-url> <out-dir>');
  process.exit(2);
}
// seed-demo.sh's account.
const EMAIL = 'anna@example.com';
const PASSWORD = 'Demo1234#demo';

(async () => {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    locale: 'en-US',
    timezoneId: 'Europe/Vienna',
    colorScheme: 'dark',
  });
  const page = await context.newPage();

  // The page's own token, to set the theme the way the theme menu does.
  let token = '';
  page.on('response', async (r) => {
    if (/\/api\/auth\/(login|refresh)$/.test(r.url()) && r.ok()) token = (await r.json()).accessToken;
  });

  await page.goto(base);
  await page.getByLabel('Email').fill(EMAIL);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('button', { name: 'Take a note…' }).waitFor();

  // Every photo loaded, and the pointer where it hovers nothing.
  const settle = async () => {
    await page.waitForLoadState('networkidle');
    await page.waitForFunction(() => [...document.images].every((i) => i.complete && i.naturalWidth > 0));
    await page.mouse.move(700, 5);
    await page.waitForTimeout(800);
  };
  // The theme is a setting of the account, not of the browser.
  const theme = async (name) => {
    const r = await context.request.put(`${base}/api/settings`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { theme: name, globalAccentColor: 'forest' },
    });
    if (!r.ok()) throw new Error(`theme ${name}: ${r.status()}`);
    await page.reload();
    await page.getByRole('button', { name: 'Take a note…' }).waitFor();
    await page.waitForFunction((t) => document.documentElement.dataset.theme === t, name);
    await settle();
  };

  await theme('dark');
  await page.screenshot({ path: path.join(out, 'web-notes-dark.png') });

  await theme('light');
  await page.screenshot({ path: path.join(out, 'web-notes-light.png') });

  await theme('dim');
  await page.getByRole('button', { name: 'Weekend in Puglia', exact: true }).click();
  await page.getByPlaceholder('Title').waitFor();
  await settle();
  await page.screenshot({ path: path.join(out, 'web-editor-dim.png') });

  // Leave the account on the web's default theme.
  await page.keyboard.press('Escape');
  await theme('dark');
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
