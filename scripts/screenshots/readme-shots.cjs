// Render the README artwork from the checked-in captures. See README.md in this directory.
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { createRequire } = require('node:module');

const root = path.resolve(__dirname, '../..');
const { chromium } = createRequire(path.join(root, 'web/package.json'))('@playwright/test');
const output = path.join(root, 'images/readme');
const url = (file) => pathToFileURL(path.join(root, file)).href;

function phone(number) {
  const shot = url(`fastlane/metadata/android/en-US/images/phoneScreenshots/${number}.png`);
  return `<div class="phone"><div class="screen"><img src="${shot}"></div></div>`;
}

function web(theme) {
  return `<img class="web" src="${url(`images/web-notes-${theme}.png`)}">`;
}

const css = `
  * { box-sizing: border-box }
  body { margin: 0; font-family: system-ui, sans-serif }
  main { position: relative; overflow: hidden; isolation: isolate; border-radius: 18px }
  .paper {
    color: #233b30;
    background: radial-gradient(circle, #567e592b .8px, transparent .9px) 0 0 / 12px 12px,
      linear-gradient(112deg, #f2f0dc, #e2efdf 55%, #c6dfc7);
  }
  .hero { background: linear-gradient(165deg,#f1e6bc 0 19%,#d2d9a4 19% 35%,#8fae83 35% 61%,#527861 61%); }
  .web { display: block; width: 100% }
  .browser { position:absolute; left:32px; top:26px; width:805px; border-radius:10px; overflow:hidden;
    box-shadow:0 12px 32px #12342455; background:#233c31; }
  .bar { height:24px; display:flex; align-items:center; justify-content:center; gap:5px; color:#f4f0df;
    font:10px system-ui; background:radial-gradient(circle at 12px 12px,#a6bba8 2px,transparent 3px),
    radial-gradient(circle at 21px 12px,#a6bba8 2px,transparent 3px),radial-gradient(circle at 30px 12px,#a6bba8 2px,transparent 3px); }
  .bar img {width:14px;height:14px}
  .hero .phone {position:absolute;--device:201px;right:29px;top:57px;transform:rotate(2deg)}
  .compact .browser {left:12px;top:16px;width:490px}
  .compact .phone {--device:146px;right:12px;top:42px}
  .pair { display: grid; grid-template-columns: 2fr 1fr; gap: 10px; border-radius: 0 }
  .panel { position: relative; overflow: hidden; border-radius: 16px }
  .night { background: radial-gradient(ellipse at 10% 110%, #47634f, transparent 75%), #1b2d25; color: #f3f3de }
  .mobile { background-color: #d9e6ce }
  h2 { margin: 18px 22px; font: 27px Georgia, serif }
  .night .web { position: absolute; top: 63px; left: 22px; width: calc(100% - 44px) }
  .phone {
    position: relative; width: var(--device); padding: 5px; border: 1px solid #191d1a; border-radius: 24px;
    background: linear-gradient(145deg, #757b77, #181b1a 22%, #0e100f 64%, #555b57);
    box-shadow: inset 0 0 0 1px #ffffff55, 0 12px 18px -8px #152a2280;
  }
  .phone::before, .phone::after { content: ''; position: absolute; right: -3px; width: 2px; border-radius: 2px; background: #383d39 }
  .phone::before { top: 22%; height: 6% }
  .phone::after { top: 31%; height: 11% }
  .screen { position: relative; overflow: hidden; aspect-ratio: 1080 / 2400; border-radius: 18px; background: #080908 }
  /* finish.py pads each side for the stores. Show the original screen inside the bezel. */
  .screen img { width: 125%; margin-left: -12.5%; display: block }
  .screen::after { content: ''; position: absolute; top: 1.15%; left: 50%; transform: translateX(-50%);
    width: 2.1%; aspect-ratio: 1; border-radius: 50%; background: #111514; box-shadow: 0 0 0 .5px #424744 }
  .mobile .phone { --device: 156px; margin: 0 auto }
  .gallery { display: flex; justify-content: space-evenly; padding: 18px 0 14px; text-align: center; --device: 195px }
  .gallery .phone { margin: 0 auto }
  .gallery strong { display: block; margin-top: 14px; font-size: 16px; font-weight: 600 }
`;

const gallery = (items) => items.map(([number, label]) =>
  `<div>${phone(number)}<strong>${label}</strong></div>`).join('');

const cover = `<div class="browser"><div class="bar"><img src="${url('web/public/keepit-icon.svg')}">keepIT</div>${web('light')}</div>${phone(1)}`;

const scenes = [
  ['light', 1000, 558, 'hero', cover],
  ['cover-compact', 390, 388, 'hero compact', cover],
  ['dark-and-android', 960, 411, 'pair',
    `<section class="panel night"><h2>Dark mode</h2>${web('dark')}</section>
     <section class="panel mobile paper"><h2>On Android</h2>${phone(1)}</section>`],
  ['android-notes', 468, 480, 'gallery paper', gallery([[3, 'Pinned notes'], [2, 'Select multiple notes']])],
  ['android-checklist-widget', 468, 480, 'gallery paper', gallery([[5, 'Shared checklist'], [7, 'Home-screen widget']])],
];

async function render() {
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ deviceScaleFactor: 2 });
    await page.goto(url('README.md'));
    for (const [name, width, height, classes, body] of scenes) {
      await page.setViewportSize({ width, height });
      await page.setContent(`<style>${css}</style><main class="${classes}" style="width:${width}px;height:${height}px">${body}</main>`);
      await page.locator('img').evaluateAll(images => Promise.all(images.map(img => img.decode())));
      const png = await page.screenshot({ omitBackground: true });
      // Chromium's WebP encoder keeps the photo-heavy gallery small enough for a first visit.
      const webp = await page.evaluate(async data => {
        const image = new Image();
        image.src = 'data:image/png;base64,' + data;
        await image.decode();
        const canvas = document.createElement('canvas');
        canvas.width = image.width;
        canvas.height = image.height;
        canvas.getContext('2d').drawImage(image, 0, 0);
        return canvas.toDataURL('image/webp', 0.94).split(',')[1];
      }, png.toString('base64'));
      await fs.writeFile(path.join(output, name + '.webp'), Buffer.from(webp, 'base64'));
    }
  } finally {
    await browser.close();
  }
}

render().catch(error => { console.error(error); process.exitCode = 1; });
