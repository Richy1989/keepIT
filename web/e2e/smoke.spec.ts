import { crc32, deflateSync } from 'node:zlib';
import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * The web app in a real browser, against a keepIT that is already running (see
 * playwright.config.ts). In CI that is the image as it ships, so this is the one check that runs
 * the built bundle under nginx's headers. What it can see and nothing else can:
 *
 *  - the Content-Security-Policy against what the bundle really does — a photo is shown from a
 *    `blob:` URL, and a policy that forgets `blob:` leaves an empty box that no API test notices;
 *  - the session living where it should: the access token in memory, so a new tab has only the
 *    httpOnly refresh cookie to sign in with;
 *  - the realtime hub as a WebSocket through the proxy, which needs nginx to pass the upgrade.
 *
 * One test, in the order a new user meets the app. Everything is found the way a person finds it
 * (labels, button names, placeholders), so a renamed class changes nothing here and a button that
 * lost its name fails.
 */

const run = `${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;
const email = `ui-smoke-${run}@example.com`;
const password = 'Smoke-test-pass-1';

// What the page threw, and what the Content-Security-Policy refused it. A refused resource throws
// nothing, it is only missing, so the run collects these and is held to them afterwards, pass or
// fail: when a step fails because of one, this is the line that says why. 401s are not collected:
// the first load asks for a session it doesn't have yet, and the browser logs that as an error.
const problems: string[] = [];

test.beforeEach(({ context }) => {
  context.on('weberror', (e) => problems.push(`uncaught: ${e.error().message}`));
  context.on('console', (message) => {
    if (/Content.Security.Policy/i.test(message.text())) problems.push(message.text());
  });
});

test.afterEach(() => {
  expect(problems, 'the page threw, or the Content-Security-Policy refused something it needs').toEqual([]);
});

test('sign up, add a note with a photo, see it on a second tab, sign in again', async ({ page, context }) => {
  const photoNote = `Photo note ${run}`;
  const liveNote = `Live note ${run}`;

  await test.step('sign up', async () => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Sign up' }).click();
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill(password);
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.getByRole('button', { name: 'Take a note…' })).toBeVisible();
  });

  await test.step('add a note with a photo', async () => {
    await page.getByRole('button', { name: 'Take a note…' }).click();
    await page.getByPlaceholder('Title').fill(photoNote);
    await page.getByPlaceholder('Take a note…').fill('Written by the browser smoke test.');
    // Through the button and the file dialog it opens, as a person attaches a photo.
    const dialog = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: 'Add image' }).click();
    await (await dialog).setFiles({ name: 'photo.png', mimeType: 'image/png', buffer: png(640, 400) });
    await page.getByRole('button', { name: 'Save' }).click();
    await expectPhotoCard(noteCard(page, photoNote));
  });

  await test.step('a second tab signs in by itself and gets a new note live', async () => {
    const second = await context.newPage();
    // The hub's first frame is the server accepting the handshake; from then on it pushes to
    // this tab. Waiting for it is what makes the next step a test of the push: with focus
    // refetching off, nothing else would bring the new note here. Listening for a WebSocket
    // also holds the proxy to passing the upgrade, where SignalR would otherwise fall back to
    // a slower transport and still pass.
    let hubConnected = false;
    second.on('websocket', (socket) => {
      if (socket.url().includes('/api/realtime')) socket.once('framereceived', () => (hubConnected = true));
    });
    await second.goto('/');
    // No sign-in form: the refresh cookie is all this tab has, and the photo comes from the
    // server, not from the first tab's cache.
    await expectPhotoCard(noteCard(second, photoNote));
    await expect.poll(() => hubConnected, 'the realtime hub connected as a WebSocket').toBe(true);

    await page.getByRole('button', { name: 'Take a note…' }).click();
    await page.getByPlaceholder('Title').fill(liveNote);
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(noteCard(second, liveNote)).toBeVisible();
    await second.close();
  });

  await test.step('sign out and in again', async () => {
    await page.getByRole('button', { name: 'Account', exact: true }).click();
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill(password);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(noteCard(page, liveNote)).toBeVisible();
    await expectPhotoCard(noteCard(page, photoNote));
  });
});

/** A note's card in the grid: a button named after the note's title. */
function noteCard(page: Page, title: string): Locator {
  return page.getByRole('button', { name: title, exact: true });
}

/**
 * A photo card shows its photo twice, as the hero and as the blurred copy the text sits on, and
 * both must have decoded: an `<img>` the browser was refused, or handed bytes it can't read, stays
 * in the page with no natural size.
 */
async function expectPhotoCard(card: Locator) {
  await expect(card).toBeVisible();
  const decoded = (images: HTMLImageElement[]) => images.map((image) => image.naturalWidth > 0);
  await expect.poll(() => card.locator('img').evaluateAll(decoded)).toEqual([true, true]);
}

/**
 * A PNG made here, so the test carries no image file: a colour gradient of the given size. The
 * same trick, for the same reason, as the photo in deploy/smoke-test.sh.
 */
function png(width: number, height: number): Buffer {
  const chunk = (kind: string, data: Buffer) => {
    const body = Buffer.concat([Buffer.from(kind, 'latin1'), data]);
    const frame = Buffer.alloc(4 + body.length + 4);
    frame.writeUInt32BE(data.length, 0);
    body.copy(frame, 4);
    frame.writeUInt32BE(crc32(body), 4 + body.length);
    return frame;
  };

  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header.set([8, 2, 0, 0, 0], 8); // 8 bits per channel, RGB, not interlaced

  // Each row is a filter byte (0: none) followed by its pixels.
  const stride = 1 + width * 3;
  const rows = Buffer.alloc(height * stride);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      rows.set([(x * 255) / width, (y * 255) / height, 160], y * stride + 1 + x * 3);
    }
  }

  return Buffer.concat([
    Buffer.from('\x89PNG\r\n\x1a\n', 'latin1'),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(rows)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}
