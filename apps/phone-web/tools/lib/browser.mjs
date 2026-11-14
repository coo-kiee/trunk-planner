// A real system Chrome with a brand-new temporary profile. playwright-core downloads no browser.
import { existsSync } from 'node:fs';
import { chromium } from 'playwright-core';

const CANDIDATES = [process.env.CHROME_PATH, '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/usr/bin/google-chrome', '/usr/bin/chromium'];

export function chromePath() {
  const found = CANDIDATES.find((path) => path && existsSync(path));
  if (!found) throw new Error('system Chrome not found; set CHROME_PATH');
  return found;
}

export const launchChrome = (args = []) => chromium.launch({ executablePath: chromePath(), headless: true, args });
export const VIEWPORTS = [{ name: 'phone360', width: 360, height: 780 }, { name: 'phone390', width: 390, height: 844 }, { name: 'desktop1440', width: 1440, height: 900 }];
