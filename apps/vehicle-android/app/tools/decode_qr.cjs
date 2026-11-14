// Decodes the QR from raw screen bytes the way a phone camera would read the pixels. Prints only the decoded text.
const { readFileSync } = require('node:fs');
const { createRequire } = require('node:module');
const [file, width, height] = process.argv.slice(2);
const require2 = createRequire(process.argv[5] + '/');
const jsQR = require2('jsqr');
const data = new Uint8ClampedArray(readFileSync(file));
const code = (jsQR.default ?? jsQR)(data, Number(width), Number(height));
process.stdout.write(code ? code.data : '');
