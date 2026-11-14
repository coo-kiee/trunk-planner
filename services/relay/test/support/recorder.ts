// Records each case with expected/actual/grade as JSON lines; run-suite.ts turns them into evidence with argv/exit/hash.
import { appendFileSync } from 'node:fs';
import { test } from 'node:test';

export type Grade = 'real_postgres_local_integration' | 'local_tls_real_postgres' | 'unit';

export interface CaseRecord { id: string; ce: string[]; grade: Grade; expected: string; actual: unknown; passed: boolean; error?: string }

/** Case bodies return the observed facts; assertion failures are recorded and rethrown. */
export function scenario(id: string, meta: { ce?: string[]; grade: Grade; expected: string }, body: () => Promise<unknown> | unknown): void {
  test(id, async () => {
    const partial = process.env.RELAY_EVIDENCE_PARTIAL;
    const write = (record: CaseRecord) => { if (partial) appendFileSync(partial, `${JSON.stringify(record)}\n`); };
    const base = { id, ce: meta.ce ?? [], grade: meta.grade, expected: meta.expected };
    try {
      write({ ...base, actual: await body(), passed: true });
    } catch (cause) {
      write({ ...base, actual: null, passed: false, error: String((cause as Error).message).slice(0, 600) });
      throw cause;
    }
  });
}

export function recordEnvironment(facts: Record<string, unknown>): void {
  const partial = process.env.RELAY_EVIDENCE_PARTIAL;
  if (partial) appendFileSync(partial, `${JSON.stringify({ environment: facts })}\n`);
}
