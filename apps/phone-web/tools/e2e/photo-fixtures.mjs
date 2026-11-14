// Synthetic photos. Pixels come from a real canvas in Chrome; the EXIF block with fake GPS is hand-built so its removal can be proven.
const SECRET_MARKER = 'SECRET_GPS_MARKER_37.5665N';
export const EXIF_MARKER = SECRET_MARKER;
export const ORIGINAL_FILE_NAME = '우리집-주소-촬영원본.jpg';

function exifApp1() {
  // Little-endian TIFF: IFD0 { ImageDescription (0x010e), Orientation (0x0112)=6, GPSInfo (0x8825) } → GPS IFD { GPSLatitudeRef }.
  const description = Buffer.from(`${SECRET_MARKER}\0`, 'latin1');
  const header = Buffer.alloc(8);
  header.write('II', 0, 'latin1');
  header.writeUInt16LE(42, 2);
  header.writeUInt32LE(8, 4);
  const ifd0Size = 2 + 3 * 12 + 4;
  const gpsOffset = 8 + ifd0Size;
  const gpsSize = 2 + 12 + 4;
  const descriptionOffset = gpsOffset + gpsSize;
  const ifd0 = Buffer.alloc(ifd0Size);
  ifd0.writeUInt16LE(3, 0);
  const entry = (index, tag, type, count, value) => {
    const at = 2 + index * 12;
    ifd0.writeUInt16LE(tag, at);
    ifd0.writeUInt16LE(type, at + 2);
    ifd0.writeUInt32LE(count, at + 4);
    if (type === 3) ifd0.writeUInt16LE(value, at + 8);
    else ifd0.writeUInt32LE(value, at + 8);
  };
  entry(0, 0x010e, 2, description.length, descriptionOffset);
  entry(1, 0x0112, 3, 1, 6);
  entry(2, 0x8825, 4, 1, gpsOffset);
  const gps = Buffer.alloc(gpsSize);
  gps.writeUInt16LE(1, 0);
  gps.writeUInt16LE(1, 2);
  gps.writeUInt16LE(2, 4);
  gps.writeUInt32LE(2, 6);
  gps.write('N\0', 10, 'latin1');
  const tiff = Buffer.concat([header, ifd0, gps, description]);
  const body = Buffer.concat([Buffer.from('Exif\0\0', 'latin1'), tiff]);
  const segment = Buffer.alloc(4);
  segment[0] = 0xff;
  segment[1] = 0xe1;
  segment.writeUInt16BE(body.length + 2, 2);
  return Buffer.concat([segment, body]);
}

/** A 3000×1000 JPEG with a red top-left corner, carrying Orientation=6 and fake GPS: after rotation the red corner must be top-right. */
export async function makeExifPhoto(browser) {
  const page = await browser.newPage();
  try {
    const dataUrl = await page.evaluate(() => {
      const canvas = document.createElement('canvas');
      canvas.width = 3000;
      canvas.height = 1000;
      const context = canvas.getContext('2d');
      context.fillStyle = '#2060c0';
      context.fillRect(0, 0, 3000, 1000);
      context.fillStyle = '#e01010';
      context.fillRect(0, 0, 600, 300);
      return canvas.toDataURL('image/jpeg', 0.9);
    });
    const jpeg = Buffer.from(dataUrl.split(',')[1], 'base64');
    return Buffer.concat([jpeg.subarray(0, 2), exifApp1(), jpeg.subarray(2)]);
  } finally {
    await page.close();
  }
}

/** Decodes bytes in a blank page and reports dimensions and the colours in two corners. */
export async function inspectPixels(browser, bytes) {
  const page = await browser.newPage();
  try {
    return await page.evaluate(async (dataUrl) => {
      const image = new Image();
      image.src = dataUrl;
      await image.decode();
      const canvas = document.createElement('canvas');
      canvas.width = image.naturalWidth;
      canvas.height = image.naturalHeight;
      const context = canvas.getContext('2d');
      context.drawImage(image, 0, 0);
      const pixel = (x, y) => [...context.getImageData(x, y, 1, 1).data].slice(0, 3);
      return { width: image.naturalWidth, height: image.naturalHeight, topRight: pixel(canvas.width - 20, 20), topLeft: pixel(20, 20), bottomLeft: pixel(20, canvas.height - 20) };
    }, `data:image/jpeg;base64,${bytes.toString('base64')}`);
  } finally {
    await page.close();
  }
}

/** Walks the JPEG markers before the first scan, the same way a reader would look for metadata. */
export function jpegSegments(bytes) {
  const markers = [];
  let offset = 2;
  while (offset + 4 <= bytes.length && bytes[offset] === 0xff) {
    const marker = bytes[offset + 1];
    markers.push(`0x${marker.toString(16)}`);
    if (marker === 0xda) break;
    offset += 2 + bytes.readUInt16BE(offset + 2);
  }
  return markers;
}
