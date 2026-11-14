// Turns a chosen file into a small, metadata-free JPEG. Only the result is ever stored; the original file is never kept.
// A photo helps identify the item. Nothing here reads, estimates or fills a dimension.
import { isCleanJpeg, stripJpegMetadata } from '../../shared/lib/jpeg-structure.ts';

// Same limits as relay-config-v1 (photoMaxDimensionPx, photoDecodedBytes).
export const PHOTO_MAX_EDGE_PX = 2048;
export const PHOTO_MAX_BYTES = 2 * 1024 * 1024;
// Decoding is the expensive step, so obviously oversized input stops before it.
export const INPUT_MAX_BYTES = 40 * 1024 * 1024;
export const INPUT_MAX_PIXELS = 100_000_000;
const QUALITY_STEPS = [0.85, 0.72, 0.6, 0.48];

export type PhotoFailure = 'NOT_IMAGE' | 'INPUT_TOO_LARGE' | 'DECODE_FAILED' | 'TOO_MANY_PIXELS' | 'ENCODE_FAILED' | 'POLICY_FAILED' | 'NO_WEBCRYPTO';

export class PhotoError extends Error {
  readonly failure: PhotoFailure;
  constructor(failure: PhotoFailure) {
    super(failure);
    this.failure = failure;
  }
}

export interface ProcessedPhoto { blob: Blob; bytes: Uint8Array; sha256: string; width: number; height: number; mime: 'image/jpeg' }

export const scaledSize = (width: number, height: number, maxEdge: number): { width: number; height: number } => {
  const scale = Math.min(1, maxEdge / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
};

/** An <img> applies EXIF orientation when decoding, so the rotated pixels survive once the EXIF block is gone. */
async function decode(file: File): Promise<HTMLImageElement> {
  const url = URL.createObjectURL(file);
  try {
    const image = new Image();
    image.src = url;
    await image.decode();
    return image;
  } catch {
    throw new PhotoError('DECODE_FAILED');
  } finally {
    URL.revokeObjectURL(url);
  }
}

function encode(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new PhotoError('ENCODE_FAILED'))), 'image/jpeg', quality);
  });
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  if (!crypto.subtle) throw new PhotoError('NO_WEBCRYPTO');
  const digest = await crypto.subtle.digest('SHA-256', bytes as BufferSource);
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, '0')).join('');
}

export async function processPhoto(file: File): Promise<ProcessedPhoto> {
  if (file.type && !file.type.startsWith('image/')) throw new PhotoError('NOT_IMAGE');
  if (file.size > INPUT_MAX_BYTES) throw new PhotoError('INPUT_TOO_LARGE');
  const image = await decode(file);
  if (image.naturalWidth * image.naturalHeight > INPUT_MAX_PIXELS) throw new PhotoError('TOO_MANY_PIXELS');

  const { width, height } = scaledSize(image.naturalWidth, image.naturalHeight, PHOTO_MAX_EDGE_PX);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  if (!context) throw new PhotoError('ENCODE_FAILED');
  context.fillStyle = '#ffffff'; // JPEG has no alpha; this is image data, not interface colour.
  context.fillRect(0, 0, width, height);
  context.drawImage(image, 0, 0, width, height);

  for (const quality of QUALITY_STEPS) {
    const encoded = await encode(canvas, quality);
    if (encoded.size > PHOTO_MAX_BYTES * 1.1) continue;
    const stripped = stripJpegMetadata(new Uint8Array(await encoded.arrayBuffer()));
    if (!stripped || !isCleanJpeg(stripped)) throw new PhotoError('POLICY_FAILED');
    if (stripped.length > PHOTO_MAX_BYTES) continue;
    return { blob: new Blob([stripped as BlobPart], { type: 'image/jpeg' }), bytes: stripped, sha256: await sha256Hex(stripped), width, height, mime: 'image/jpeg' };
  }
  throw new PhotoError('POLICY_FAILED');
}

export const PHOTO_FAILURE_MESSAGES: Record<PhotoFailure, string> = {
  NOT_IMAGE: '이미지 파일이 아닙니다. 사진 없이도 계속할 수 있습니다.',
  INPUT_TOO_LARGE: '사진 파일이 너무 큽니다(40MB 이하). 다른 사진을 고르거나 사진 없이 계속하세요.',
  DECODE_FAILED: '이 사진을 열 수 없습니다. 다른 사진을 고르거나 사진 없이 계속하세요.',
  TOO_MANY_PIXELS: '사진 해상도가 너무 높습니다. 다른 사진을 고르거나 사진 없이 계속하세요.',
  ENCODE_FAILED: '사진을 줄이지 못했습니다. 다른 사진을 고르거나 사진 없이 계속하세요.',
  POLICY_FAILED: '사진을 전송 기준(2MB 이하, 메타데이터 없음)에 맞추지 못했습니다. 사진 없이 계속할 수 있습니다.',
  NO_WEBCRYPTO: '이 브라우저에서 사진 확인값을 만들 수 없습니다. 사진 없이 계속할 수 있습니다.',
};
