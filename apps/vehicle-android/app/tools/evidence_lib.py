"""One JSON file per acceptance case. A case passes only if every recorded observation held and nothing raised."""
import json, time, traceback
from ui_driver import EVIDENCE, SERIAL, StepFailed, now_iso


class Case:
    def __init__(self, ident, responsibility, grade, context):
        self.record = {
            'id': ident, 'owner': 'vehicle-ui', 'responsibility': responsibility, 'status': 'not_run',
            'evidence_grade': grade, 'real_vehicle': 'not_tested', 'real_phone': 'not_tested',
            'serial': SERIAL, 'started': now_iso(), 'observations': [], 'captures': [], 'inputs': {}, **context,
        }

    def check(self, name, passed, expected=None, actual=None):
        self.record['observations'].append({'check': name, 'pass': bool(passed), 'expected': expected, 'actual': actual})
        print(('  ok  ' if passed else '  FAIL ') + name + ('' if passed else f' expected={expected} actual={actual}'), flush=True)
        return passed

    def equal(self, name, actual, expected):
        return self.check(name, actual == expected, expected, actual)

    def contains(self, name, text, needle):
        return self.check(name, needle in text, needle, text)

    def lacks(self, name, text, needle):
        return self.check(name, needle not in text, f'no "{needle}"', text)

    def input(self, **values):
        self.record['inputs'].update(values)


class Runner:
    def __init__(self, ui, context, prefix=''):
        self.ui = ui
        self.context = context
        self.prefix = prefix
        self.results = {}

    def run(self, ident, responsibility, grade, body):
        print(f'== {ident}', flush=True)
        case = Case(ident, responsibility, grade, self.context)
        started = time.time()
        try:
            body(case)
            failed = [o for o in case.record['observations'] if not o['pass']]
            case.record['status'] = 'fail' if failed or not case.record['observations'] else 'pass'
        except Exception as error:  # A harness or app failure is a failed case, never a skipped one.
            case.record['status'] = 'fail'
            case.record['error'] = f'{type(error).__name__}: {error}'
            case.record['traceback'] = traceback.format_exc()[-1500:]
            print(f'  ERROR {error}', flush=True)
            try:
                case.record['captures'].append(self.ui.capture(f'{self.prefix}{ident}-error'))
            except Exception:
                pass
        case.record['seconds'] = round(time.time() - started, 1)
        (EVIDENCE / f'{self.prefix}{ident}.json').write_text(json.dumps(case.record, ensure_ascii=False, indent=1))
        self.results[ident] = case.record['status']
        print(f'== {ident}: {case.record["status"]} ({case.record["seconds"]}s)', flush=True)
        return case.record['status']
