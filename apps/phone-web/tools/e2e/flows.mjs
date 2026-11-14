// User-level steps against the real app. Everything goes through visible controls, as a person would.
import { join } from 'node:path';
import { evidenceDir } from '../lib/evidence.mjs';

export const SCREEN_DIR = join(evidenceDir, 'screens');

export async function openApp(page, url) {
  await page.goto(url, { waitUntil: 'load' });
  await page.waitForSelector('main[data-loaded="true"]', { timeout: 20_000 });
}

export const goStep = (page, step) => page.locator(`[data-step="${step}"]`).click();
export const button = (page, name) => page.getByRole('button', { name, exact: true });
export const fieldInput = (page, name) => page.locator(`[name="${name}"]`);
export const statusState = (page, state) => page.locator(`[data-ds="status"][data-state="${state}"]`);

export async function waitForStatus(page, state, timeout = 20_000) {
  await statusState(page, state).first().waitFor({ timeout });
}

export const DEFAULT_ITEM = { name: '시험 상자', shape: 'BOX', x: '400', y: '300', z: '200', error: '5', source: 'USER_TAPE', rotation: 'KEEP_UPRIGHT', stackable: 'true', priority: '1' };

export async function startItem(page, item = DEFAULT_ITEM) {
  await goStep(page, 'P1');
  const start = button(page, '새 물품 시작');
  if (await start.isVisible()) await start.click();
  await fieldInput(page, 'name').fill(item.name);
  await fieldInput(page, 'shapeKind').selectOption(item.shape);
}

export async function fillMeasurements(page, item = DEFAULT_ITEM) {
  await button(page, '다음: 치수와 취급조건').click();
  await fieldInput(page, 'dimX').fill(item.x);
  await fieldInput(page, 'dimY').fill(item.y);
  await fieldInput(page, 'dimZ').fill(item.z);
  if (item.legX) await fieldInput(page, 'legX').fill(item.legX);
  if (item.footZ) await fieldInput(page, 'footZ').fill(item.footZ);
  await fieldInput(page, 'errorMm').fill(item.error);
  await fieldInput(page, 'measurementSource').selectOption(item.source);
  await fieldInput(page, 'rotationRule').selectOption(item.rotation);
  await fieldInput(page, 'stackable').selectOption(item.stackable);
  await fieldInput(page, 'removalPriority').fill(item.priority);
}

export async function confirmAndEnqueue(page) {
  await fieldInput(page, 'confirmedDimensions').check();
  await fieldInput(page, 'dimensionsConsent').check();
  await button(page, '전송함에 넣기').click();
  await page.waitForSelector('main[data-screen="P3"]', { timeout: 15_000 });
}

/** The full P1→P2→P3 path without a photo. */
export async function registerItem(page, item = DEFAULT_ITEM) {
  await startItem(page, item);
  await fillMeasurements(page, item);
  await confirmAndEnqueue(page);
}

export const fact = (page, id) => page.locator(`[data-fact="${id}"] dd`).first();

/** Reads one IndexedDB store through the page, for evidence only. */
export const readStore = (page, store) => page.evaluate((name) => new Promise((resolve, reject) => {
  const open = indexedDB.open('cargo-phone');
  open.onerror = () => reject(open.error);
  open.onsuccess = () => {
    const db = open.result;
    const request = db.transaction(name).objectStore(name).getAll();
    request.onsuccess = () => { db.close(); resolve(request.result); };
    request.onerror = () => { db.close(); reject(request.error); };
  };
}), store);

export async function shot(page, name) {
  const path = join(SCREEN_DIR, `${name}.png`);
  await page.screenshot({ path, fullPage: true });
  return `screens/${name}.png`;
}

/** Facts a person would notice: sideways scrolling, clipped controls, too-small touch targets. */
export const auditLayout = (page) => page.evaluate(() => {
  const root = document.documentElement;
  const small = [];
  for (const element of document.querySelectorAll('main button, main a, main select, main input:not([type=checkbox]), main label:has(input[type=checkbox])')) {
    const box = element.getBoundingClientRect();
    if (box.width === 0) continue;
    if (box.height < 44) small.push({ tag: element.tagName, text: (element.textContent ?? '').slice(0, 30), height: Math.round(box.height) });
  }
  const overflowing = [...document.querySelectorAll('main *')].filter((element) => {
    const box = element.getBoundingClientRect();
    return box.width > 0 && box.right > root.clientWidth + 1;
  }).slice(0, 5).map((element) => `${element.tagName}.${element.className}`);
  return { horizontalScroll: root.scrollWidth > root.clientWidth, scrollWidth: root.scrollWidth, clientWidth: root.clientWidth, smallTargets: small, overflowing };
});
