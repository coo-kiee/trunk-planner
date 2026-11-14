// Streaming JSON body reader with a hard byte cap; the cap applies while reading, not after buffering everything.
import type { IncomingMessage } from 'node:http';
import { RelayError } from '../domain/errors.ts';

export interface ParsedBody { value: unknown; bytes: number }

export async function readJsonBody(req: IncomingMessage, maxBytes: number): Promise<ParsedBody> {
  const contentType = (req.headers['content-type'] ?? '').split(';')[0]?.trim().toLowerCase();
  if (contentType !== 'application/json') throw new RelayError(415, 'CONTENT_TYPE_UNSUPPORTED');
  const declared = Number(req.headers['content-length'] ?? 0);
  if (declared > maxBytes) throw new RelayError(413, 'BODY_TOO_LARGE', { maxBytes });

  const chunks: Buffer[] = [];
  let total = 0;
  try {
    for await (const chunk of req as AsyncIterable<Buffer>) {
      total += chunk.length;
      if (total > maxBytes) throw new RelayError(413, 'BODY_TOO_LARGE', { maxBytes });
      chunks.push(chunk);
    }
  } catch (cause) {
    if (cause instanceof RelayError) throw cause;
    throw new RelayError(400, 'BODY_ABORTED');
  }
  try {
    return { value: JSON.parse(Buffer.concat(chunks, total).toString('utf8')), bytes: total };
  } catch {
    throw new RelayError(400, 'INVALID_JSON');
  }
}
