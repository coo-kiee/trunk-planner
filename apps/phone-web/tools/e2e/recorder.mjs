// Collects expected/actual pairs and writes evidence even when a scenario throws. Exit code comes from what was recorded.
// JSON key order is not meaningful (the relay stores JSONB), so values are compared canonically.
const canonical = (value) => JSON.stringify(value, (_key, inner) => (inner && typeof inner === 'object' && !Array.isArray(inner) ? Object.fromEntries(Object.entries(inner).sort(([a], [b]) => a.localeCompare(b))) : inner));

export class Recorder {
  constructor() {
    this.scenarios = [];
    this.current = null;
  }

  async scenario(group, name, grade, work) {
    const record = { group, name, grade, status: 'running', checks: [], started_at: new Date().toISOString() };
    this.scenarios.push(record);
    this.current = record;
    process.stdout.write(`▶ [${group}] ${name}\n`);
    try {
      await work(record);
      record.status = record.checks.every((check) => check.passed) ? 'passed' : 'failed';
    } catch (cause) {
      record.status = 'failed';
      record.error = String(cause?.stack ?? cause).slice(0, 1800);
    }
    record.finished_at = new Date().toISOString();
    const failed = record.checks.filter((check) => !check.passed);
    process.stdout.write(`  ${record.status === 'passed' ? '✓' : '✗'} ${record.checks.length - failed.length}/${record.checks.length}${record.error ? ` ERROR ${record.error.slice(0, 400)}` : ''}\n`);
    for (const check of failed) process.stdout.write(`    ✗ ${check.name}: expected ${JSON.stringify(check.expected)} got ${JSON.stringify(check.actual)}\n`);
    return record;
  }

  check(name, expected, actual) {
    const passed = canonical(expected) === canonical(actual);
    this.current.checks.push({ name, expected, actual, passed });
    return passed;
  }

  truthy(name, actual, note) {
    this.current.checks.push({ name, expected: true, actual: Boolean(actual), passed: Boolean(actual), ...(note === undefined ? {} : { note }) });
    return Boolean(actual);
  }

  note(name, value) {
    this.current.notes = { ...(this.current.notes ?? {}), [name]: value };
  }

  select(group) {
    return this.scenarios.filter((record) => record.group === group);
  }
  get allPassed() {
    return this.scenarios.length > 0 && this.scenarios.every((record) => record.status === 'passed');
  }
}
