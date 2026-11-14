/** Typed protocol failure; `details` holds only non-secret state values the client branches on. */
export class RelayError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: Record<string, unknown>;
  readonly retryAfterSeconds?: number;

  constructor(status: number, code: string, details: Record<string, unknown> = {}, retryAfterSeconds?: number) {
    super(code);
    this.status = status;
    this.code = code;
    this.details = details;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
