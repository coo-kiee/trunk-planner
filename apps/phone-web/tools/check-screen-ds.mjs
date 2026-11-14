// node apps/phone-web/tools/check-screen-ds.mjs  →  evidence/product/phone-web/ds-consumption.json
// Proves P0~P3 consume the shared UI and generated tokens: source call sites, resolved tokens, computed styles in a real browser at
// 360/390/1440, and that overriding a token custom property actually moves the rendered value. Import-only or hard-coded use fails.
// It writes evidence and exits non-zero on any failure, including a missing build. The product token source is never changed.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { chromePath, launchChrome, VIEWPORTS } from './lib/browser.mjs';
import { analyzeCss, analyzeTsx, readDefinedTokens, scanSources, EXCEPTIONS } from './lib/ds-analyzer.mjs';
import { appRoot, repoRoot, writeEvidence } from './lib/evidence.mjs';
import { freePort, sleep, startNext } from './lib/stack.mjs';
import { buildSeed, SEMANTIC_STATES } from './lib/ds-seed.mjs';

const started = new Date().toISOString();
const failures = [];
const fail = (where, detail) => failures.push({ where, detail });

const luminance = (hex) => {
  const channels = [1, 3, 5].map((start) => parseInt(hex.slice(start, start + 2), 16) / 255).map((value) => (value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4));
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
};
const contrastRatio = (a, b) => {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return Math.round(((light + 0.05) / (dark + 0.05)) * 100) / 100;
};

/** The foreground/background pairs the screens actually use, from the generated values. AA normal text is 4.5. */
function contrastTable(defined) {
  const pairs = [
    ['--cargo-color-text', '--cargo-color-surface', '본문/패널'],
    ['--cargo-color-text', '--cargo-color-background', '입력값/입력칸'],
    ['--cargo-color-background', '--cargo-color-accent', '주요 버튼'],
    ['--cargo-color-unverified', '--cargo-color-surface', '힌트·대기·미확인·비활성 버튼'],
    ['--cargo-color-accent', '--cargo-color-surface', '전송 중·링크'],
    ['--cargo-color-uncertain', '--cargo-color-surface', '서버 수신'],
    ['--cargo-color-clear', '--cargo-color-surface', '차량 반영'],
    ['--cargo-color-conflict', '--cargo-color-surface', '충돌·오류'],
    ['--cargo-color-stale', '--cargo-color-surface', '만료·해제'],
    ['--cargo-color-unverified', '--cargo-color-background', '단계 화면 제목 외 보조 글자'],
  ];
  return pairs.map(([foreground, background, use]) => ({ use, foreground, background, ratio: contrastRatio(defined.get(foreground), defined.get(background)), minimum: 4.5 }));
}

function staticPhase() {
  const scan = scanSources(appRoot, repoRoot);
  const allCalls = scan.tsx.flatMap((file) => file.calls);
  const violations = [...scan.tsx.flatMap((file) => file.violations), ...scan.css.flatMap((file) => file.violations), ...scan.uiTsx.flatMap((file) => file.violations)];
  for (const violation of violations) fail('static', violation);

  const needs = {
    P0: { files: ['screens/connect/ConnectScreen.tsx'], components: ['Button', 'Status', 'Panel', 'TextLink'] },
    P1: { files: ['screens/photo-shape/PhotoShapeScreen.tsx'], components: ['Button', 'Field', 'Status', 'Panel'] },
    P2: { files: ['screens/dimensions/DimensionsScreen.tsx'], components: ['Button', 'Field', 'Status', 'Panel'] },
    P3: { files: ['screens/outbox/OutboxScreen.tsx', 'screens/outbox/OutboxCard.tsx'], components: ['Button', 'Status', 'Panel'] },
  };
  const coverage = {};
  for (const [screen, need] of Object.entries(needs)) {
    const calls = allCalls.filter((call) => need.files.includes(call.file));
    coverage[screen] = { files: need.files, calls };
    for (const component of need.components) {
      if (!calls.some((call) => call.component === component)) fail('static', { screen, rule: 'component-not-called', component });
    }
  }
  if (scan.render3d.length > 0 || scan.render3dDeps.length > 0) fail('static', { rule: '3d-render-on-phone', files: scan.render3d, deps: scan.render3dDeps });

  const contrast = contrastTable(scan.defined);
  for (const row of contrast) if (row.ratio < row.minimum) fail('contrast', row);

  // The analyzer must catch the deliberate bypasses, or a clean result above would mean nothing.
  const defined = readDefinedTokens([join(repoRoot, 'packages/design-tokens/tokens.css'), join(appRoot, 'shared/ui/semantic-tokens.css')]);
  const negative = [
    { fixture: 'tools/fixtures/bypass-screen.tsx', result: analyzeTsx(join(appRoot, 'tools/fixtures/bypass-screen.tsx'), appRoot), mustReport: ['raw-control', 'inline-style', 'color-literal'] },
    { fixture: 'tools/fixtures/import-only-screen.tsx', result: analyzeTsx(join(appRoot, 'tools/fixtures/import-only-screen.tsx'), appRoot), mustReport: ['import-only'] },
    { fixture: 'tools/fixtures/bypass.module.css', result: analyzeCss(join(appRoot, 'tools/fixtures/bypass.module.css'), appRoot, defined), mustReport: ['color-literal', 'literal-value', 'unknown-token'] },
  ].map(({ fixture, result, mustReport }) => {
    const reported = [...new Set(result.violations.map((violation) => violation.rule))];
    const caught = mustReport.every((rule) => reported.includes(rule));
    if (!caught) fail('negative-fixture', { fixture, mustReport, reported });
    return { fixture, must_report: mustReport, reported, caught };
  });

  return {
    source_call_sites: allCalls,
    screen_coverage: coverage,
    css_token_usages: scan.css.flatMap((file) => file.usages),
    defined_tokens: Object.fromEntries(scan.defined),
    violations,
    render_3d_sources: scan.render3d,
    render_3d_dependencies: scan.render3dDeps,
    exceptions: EXCEPTIONS,
    negative_fixtures: negative,
    contrast,
  };
}

// Runs inside the page: reads each design-system element and compares it with what its tokens resolve to.
const collectInPage = () => {
  const probe = document.createElement('div');
  document.body.append(probe);
  const rootStyle = getComputedStyle(document.documentElement);
  const resolved = (property, name) => {
    probe.style.setProperty(property, `var(${name})`);
    const value = getComputedStyle(probe).getPropertyValue(property);
    probe.style.removeProperty(property);
    return value;
  };
  const color = (name) => resolved('color', name);
  const length = (name) => resolved('padding-left', name);
  const size = (name) => resolved('font-size', name);
  const rows = [];
  const expect = (element, component, property, token, actual, wanted) => rows.push({ component, detail: element.dataset.variant ?? element.dataset.tone ?? element.dataset.kind ?? '', state: element.dataset.state ?? null, property, token, actual, expected: wanted, match: actual === wanted });

  for (const element of document.querySelectorAll('main [data-ds="button"]')) {
    if (element.disabled) continue;
    const style = getComputedStyle(element);
    const variant = element.dataset.variant;
    expect(element, 'Button', 'min-height', '--cargo-touch-target', style.minHeight, length('--cargo-touch-target'));
    expect(element, 'Button', 'font-size', variant === 'step' ? '--cargo-type-small' : '--cargo-type-body', style.fontSize, size(variant === 'step' ? '--cargo-type-small' : '--cargo-type-body'));
    if (variant === 'primary') {
      expect(element, 'Button', 'background-color', '--cargo-color-accent', style.backgroundColor, color('--cargo-color-accent'));
      expect(element, 'Button', 'color', '--cargo-color-background', style.color, color('--cargo-color-background'));
    } else if (variant === 'danger') {
      expect(element, 'Button', 'color', '--cargo-color-conflict', style.color, color('--cargo-color-conflict'));
    } else if (variant === 'secondary') {
      expect(element, 'Button', 'color', '--cargo-color-text', style.color, color('--cargo-color-text'));
      expect(element, 'Button', 'border-top-color', '--cargo-color-accent', style.borderTopColor, color('--cargo-color-accent'));
    }
    if (variant !== 'step') expect(element, 'Button', 'padding-left', '--cargo-spacing-medium', style.paddingLeft, length('--cargo-spacing-medium'));
  }
  for (const element of document.querySelectorAll('main [data-ds="field"]')) {
    const style = getComputedStyle(element);
    expect(element, 'Field', 'row-gap', '--cargo-spacing-small', style.rowGap, length('--cargo-spacing-small'));
    const control = element.querySelector('input:not([type=checkbox]), select');
    if (control) {
      const controlStyle = getComputedStyle(control);
      expect(element, 'Field', 'control.background-color', '--cargo-color-background', controlStyle.backgroundColor, color('--cargo-color-background'));
      expect(element, 'Field', 'control.color', '--cargo-color-text', controlStyle.color, color('--cargo-color-text'));
      expect(element, 'Field', 'control.font-size', '--cargo-type-body', controlStyle.fontSize, size('--cargo-type-body'));
      expect(element, 'Field', 'control.padding-left', '--cargo-spacing-small', controlStyle.paddingLeft, length('--cargo-spacing-small'));
    }
    const label = element.querySelector('label span, label');
    if (label) expect(element, 'Field', 'label.font-size', '--cargo-type-body', getComputedStyle(label).fontSize, size('--cargo-type-body'));
    const error = element.querySelector('[role=alert]');
    if (error) expect(element, 'Field', 'error.color', '--cargo-color-conflict', getComputedStyle(error).color, color('--cargo-color-conflict'));
  }
  for (const element of document.querySelectorAll('main [data-ds="status"]')) {
    const token = element.dataset.token;
    const style = getComputedStyle(element);
    expect(element, 'Status', 'border-top-color', token, style.borderTopColor, color(token));
    expect(element, 'Status', 'label.color', token, getComputedStyle(element.querySelector('p')).color, color(token));
    expect(element, 'Status', 'row-gap', '--cargo-spacing-small', style.rowGap, length('--cargo-spacing-small'));
    expect(element, 'Status', 'label.font-size', '--cargo-type-body', getComputedStyle(element.querySelector('p')).fontSize, size('--cargo-type-body'));
  }
  for (const element of document.querySelectorAll('main [data-ds="panel"]')) {
    const style = getComputedStyle(element);
    expect(element, 'Panel', 'background-color', '--cargo-color-surface', style.backgroundColor, color('--cargo-color-surface'));
    expect(element, 'Panel', 'padding-left', '--cargo-spacing-medium', style.paddingLeft, length('--cargo-spacing-medium'));
    expect(element, 'Panel', 'row-gap', '--cargo-spacing-medium', style.rowGap, length('--cargo-spacing-medium'));
    const title = element.querySelector('h2');
    if (title) expect(element, 'Panel', 'title.font-size', '--cargo-type-title', getComputedStyle(title).fontSize, size('--cargo-type-title'));
  }
  probe.remove();

  const inMain = (selector) => [...document.querySelectorAll(`main ${selector}`)];
  const undeclared = inMain('button, input, select, textarea, a').filter((element) => !element.closest('[data-ds]')).map((element) => element.outerHTML.slice(0, 120));
  const inlineStyled = inMain('[style]').map((element) => element.outerHTML.slice(0, 120));
  const components = {};
  for (const element of inMain('[data-ds]')) components[element.dataset.ds] = (components[element.dataset.ds] ?? 0) + 1;
  return { rows, undeclared, inlineStyled, components, canvasCount: document.querySelectorAll('canvas').length, sampleRootToken: rootStyle.getPropertyValue('--cargo-color-accent').trim() };
};

// [generated token to override, unique value, selector, property, expected computed value]
const OVERRIDES = [
  ['Button', '--cargo-color-accent', '#010203', '[data-ds="button"][data-variant="primary"]', 'background-color', 'rgb(1, 2, 3)'],
  ['Button', '--cargo-color-background', '#040506', '[data-ds="button"][data-variant="primary"]', 'color', 'rgb(4, 5, 6)'],
  ['Button', '--cargo-spacing-medium', '31px', '[data-ds="button"][data-variant="primary"]', 'padding-left', '31px'],
  ['Button', '--cargo-spacing-large', '37px', '[data-ds="button"][data-variant="primary"]', 'min-height', '74px'],
  ['Button', '--cargo-type-body', '23px', '[data-ds="button"][data-variant="primary"]', 'font-size', '23px'],
  ['Field', '--cargo-color-background', '#070809', '[data-ds="field"] input:not([type=checkbox]), [data-ds="field"] select', 'background-color', 'rgb(7, 8, 9)'],
  ['Field', '--cargo-color-text', '#0a0b0c', '[data-ds="field"] input:not([type=checkbox]), [data-ds="field"] select', 'color', 'rgb(10, 11, 12)'],
  ['Field', '--cargo-spacing-small', '13px', '[data-ds="field"]', 'row-gap', '13px'],
  ['Field', '--cargo-type-body', '22px', '[data-ds="field"] input:not([type=checkbox]), [data-ds="field"] select', 'font-size', '22px'],
  ['Panel', '--cargo-color-surface', '#0d0e0f', '[data-ds="panel"]', 'background-color', 'rgb(13, 14, 15)'],
  ['Panel', '--cargo-spacing-medium', '29px', '[data-ds="panel"]', 'padding-left', '29px'],
  ['Panel', '--cargo-type-title', '33px', '[data-ds="panel"] h2', 'font-size', '33px'],
];
const STATUS_OVERRIDES = [
  ['pending', '--cargo-color-unverified', '#101112', 'rgb(16, 17, 18)'],
  ['sending', '--cargo-color-accent', '#131415', 'rgb(19, 20, 21)'],
  ['received', '--cargo-color-uncertain', '#161718', 'rgb(22, 23, 24)'],
  ['accepted', '--cargo-color-clear', '#191a1b', 'rgb(25, 26, 27)'],
  ['conflict', '--cargo-color-conflict', '#1c1d1e', 'rgb(28, 29, 30)'],
  ['expired', '--cargo-color-stale', '#1f2021', 'rgb(31, 32, 33)'],
  ['revoked', '--cargo-color-stale', '#222324', 'rgb(34, 35, 36)'],
  ['unknown', '--cargo-color-unverified', '#252627', 'rgb(37, 38, 39)'],
];

/** Sets one token on :root through CSSOM, reads the computed value, then restores it. Nothing in the product changes. */
const overrideInPage = ([token, value, selector, property]) => {
  const element = document.querySelector(`main ${selector}`);
  if (!element) return { found: false };
  const before = getComputedStyle(element).getPropertyValue(property);
  document.documentElement.style.setProperty(token, value);
  const during = getComputedStyle(element).getPropertyValue(property);
  document.documentElement.style.removeProperty(token);
  const after = getComputedStyle(element).getPropertyValue(property);
  return { found: true, before, during, after };
};

const seedStorage = (seed) => new Promise((resolve, reject) => {
  const open = indexedDB.open('cargo-phone');
  open.onerror = () => reject(open.error);
  open.onsuccess = () => {
    const db = open.result;
    const tx = db.transaction(['pairing', 'outbox', 'confirmed'], 'readwrite');
    tx.objectStore('pairing').put(seed.pairing, 'current');
    for (const entry of seed.entries) tx.objectStore('outbox').put(entry);
    for (const confirmed of seed.confirmed) tx.objectStore('confirmed').put(confirmed);
    tx.oncomplete = () => { db.close(); resolve(); };
    tx.onerror = () => reject(tx.error);
  };
});

async function runtimePhase(next, browser) {
  const photo = readFileSync(join(repoRoot, 'services/relay/test/fixtures/canvas-chrome.jpg'));
  const results = {};
  let overrides = null;

  for (const viewport of VIEWPORTS) {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    const perScreen = {};
    const record = async (screen) => {
      const collected = await page.evaluate(collectInPage);
      perScreen[screen] = collected;
      for (const row of collected.rows) if (!row.match) fail(`runtime ${viewport.name} ${screen}`, row);
      if (collected.undeclared.length > 0) fail(`runtime ${viewport.name} ${screen}`, { rule: 'control-outside-ds', elements: collected.undeclared });
      if (collected.inlineStyled.length > 0) fail(`runtime ${viewport.name} ${screen}`, { rule: 'inline-style-in-dom', elements: collected.inlineStyled });
      if (collected.canvasCount > 0) fail(`runtime ${viewport.name} ${screen}`, { rule: 'canvas-in-dom' });
    };
    const go = (step) => page.locator(`[data-step="${step}"]`).click();

    await page.goto(next.origin, { waitUntil: 'load' });
    await page.waitForSelector('main[data-loaded="true"]');
    await record('P0');

    await go('P1');
    await page.getByRole('button', { name: '새 물품 시작', exact: true }).click();
    await page.getByRole('button', { name: '다음: 치수와 취급조건', exact: true }).click(); // Shows the field errors.
    await page.locator('input[type=file][name=photo]').setInputFiles({ name: 'sample.jpg', mimeType: 'image/jpeg', buffer: photo });
    await page.locator('[data-photo-preview]').waitFor();
    await record('P1');

    await page.locator('[name="name"]').fill('상자');
    await page.locator('[name="shapeKind"]').selectOption('L_SHAPE');
    await page.getByRole('button', { name: '다음: 치수와 취급조건', exact: true }).click();
    await page.waitForSelector('main[data-screen="P2"]');
    await page.locator('[name="dimX"]').fill('0');
    await page.locator('[name="confirmedDimensions"]').check();
    await page.locator('[name="dimensionsConsent"]').check();
    await page.getByRole('button', { name: '전송함에 넣기', exact: true }).click();
    await page.waitForSelector('[role=alert]');
    await record('P2');

    // P3 at the app's storage boundary: every semantic state from real records, no test route in the product.
    await page.evaluate(seedStorage, buildSeed());
    // The first pending send stays "sending" until released, so that state is real too.
    let release;
    const held = new Promise((resolve) => { release = resolve; });
    await context.route('**/phone/v1/drafts', async (route) => {
      if (route.request().method() === 'OPTIONS') return route.fulfill({ status: 204, headers: { 'access-control-allow-origin': '*', 'access-control-allow-headers': 'authorization, content-type', 'access-control-allow-methods': 'GET, POST' } });
      await held;
      return route.abort().catch(() => undefined);
    });
    await page.reload({ waitUntil: 'load' });
    await page.waitForSelector('main[data-loaded="true"]');
    await go('P3');
    await page.locator('[data-ds="status"][data-state="sending"]').waitFor({ timeout: 15_000 });
    await record('P3');

    const states = await page.evaluate(() => [...document.querySelectorAll('main [data-testid^="entry-"] [data-ds="status"]')].map((element) => {
      const label = element.querySelector('p');
      return { state: element.dataset.state, tone: element.dataset.tone, token: element.dataset.token, icon: element.querySelector('span').textContent, label: label.textContent, color: getComputedStyle(label).color };
    }));
    perScreen.P3_states = states;
    for (const wanted of SEMANTIC_STATES) {
      const seen = states.find((state) => state.state === wanted.state);
      if (!seen) fail(`runtime ${viewport.name} P3`, { rule: 'semantic-state-not-rendered', state: wanted.state });
      else if (seen.token !== wanted.token) fail(`runtime ${viewport.name} P3`, { rule: 'semantic-token-mismatch', state: wanted.state, expected: wanted.token, actual: seen.token });
      else if (!seen.label || !seen.icon) fail(`runtime ${viewport.name} P3`, { rule: 'colour-only-signal', state: wanted.state });
      // An emoji paints its own colours, which would bypass the semantic token.
      else if (/\p{Emoji_Presentation}/u.test(seen.icon)) fail(`runtime ${viewport.name} P3`, { rule: 'emoji-icon-ignores-token', state: wanted.state, icon: seen.icon });
    }

    if (viewport.name === 'phone390') {
      overrides = { components: [], status: [] };
      const screens = { Button: 'P3', Panel: 'P3', Field: 'P2' };
      for (const [component, token, value, selector, property, expected] of OVERRIDES) {
        await go(screens[component]);
        const observed = await page.evaluate(overrideInPage, [token, value, selector, property]);
        const changed = observed.found && observed.during === expected && observed.after === observed.before;
        overrides.components.push({ component, token, override: value, selector, property, expected, ...observed, changed });
        if (!changed) fail('token-override', { component, token, property, expected, observed });
      }
      await go('P3');
      for (const [state, token, value, expected] of STATUS_OVERRIDES) {
        const selector = `[data-testid^="entry-"] [data-ds="status"][data-state="${state}"] p`;
        const observed = await page.evaluate(overrideInPage, [token, value, selector, 'color']);
        const changed = observed.found && observed.during === expected && observed.after === observed.before;
        overrides.status.push({ state, token, override: value, property: 'color', expected, ...observed, changed });
        if (!changed) fail('token-override', { state, token, expected, observed });
      }

      // A hard-coded control added to the live DOM must be caught by the same audit and must ignore the token.
      overrides.hardcoded_negative = await page.evaluate(() => {
        const rogue = document.createElement('button');
        rogue.textContent = 'bypass';
        rogue.style.color = 'rgb(200, 0, 0)';
        document.querySelector('main').append(rogue);
        const undeclared = [...document.querySelectorAll('main button')].filter((element) => !element.closest('[data-ds]')).length;
        const before = getComputedStyle(rogue).color;
        document.documentElement.style.setProperty('--cargo-color-text', '#0a0b0c');
        const during = getComputedStyle(rogue).color;
        document.documentElement.style.removeProperty('--cargo-color-text');
        rogue.remove();
        return { detected_as_undeclared: undeclared, token_changed_it: before !== during };
      });
      if (overrides.hardcoded_negative.detected_as_undeclared !== 1 || overrides.hardcoded_negative.token_changed_it) fail('negative-runtime', overrides.hardcoded_negative);
    }
    release();
    await sleep(50);
    await context.close();
    results[viewport.name] = perScreen;
  }
  return { results, overrides };
}

const sha256File = (path) => createHash('sha256').update(readFileSync(path)).digest('hex');
let staticResult = null;
let runtimeResult = null;
let fatal = null;
let next = null;
let browser = null;
try {
  staticResult = staticPhase();
  if (!existsSync(join(appRoot, '.next/BUILD_ID'))) throw new Error('no production build: run `npm --prefix apps/phone-web run build` first');
  // The address is closed on purpose: only the look of each state matters here, and it must not depend on a relay answering.
  next = await startNext({ port: await freePort(), relayUrl: `http://127.0.0.1:${await freePort()}` });
  browser = await launchChrome();
  runtimeResult = await runtimePhase(next, browser);
} catch (cause) {
  fatal = String(cause?.stack ?? cause).slice(0, 1800);
  fail('fatal', fatal);
} finally {
  await browser?.close();
  await next?.stop();
}

const status = failures.length === 0 && runtimeResult ? 'passed' : 'failed';
writeEvidence('ds-consumption.json', {
  schema: 'phone-web-ds-consumption-v1',
  status,
  grade: 'actual_browser_viewport_with_storage_seeded_states',
  argv: ['node', 'apps/phone-web/tools/check-screen-ds.mjs'],
  cwd: 'project root',
  exit: status === 'passed' ? 0 : 1,
  started_at: started,
  finished_at: new Date().toISOString(),
  environment: { node: process.version, browser_path: chromePath(), viewports: VIEWPORTS },
  inputs: { tokens_css_sha256: sha256File(join(repoRoot, 'packages/design-tokens/tokens.css')), semantic_tokens_css_sha256: sha256File(join(appRoot, 'shared/ui/semantic-tokens.css')), ui_module_css_sha256: sha256File(join(appRoot, 'shared/ui/ui.module.css')) },
  product_token_source_modified: false,
  boundary: 'CSS custom property 덮어쓰기는 이 검사 페이지의 메모리 안에서만 일어난다. P3 상태는 앱 저장 경계에 기록을 넣어 재현했으며, 실제 전송 흐름의 상태는 browser.json에 별도로 있다.',
  failures,
  static: staticResult,
  runtime: runtimeResult,
});
console.log(JSON.stringify({ status, failures: failures.length, sample: failures.slice(0, 6) }).slice(0, 2500));
process.exit(status === 'passed' ? 0 : 1);
