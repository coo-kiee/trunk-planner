// test:protocol — pure checks are graded `unit`; anything through the running relay is graded real PostgreSQL integration.
import { unitScenarios } from './protocol/unit.ts';
import { httpScenarios } from './protocol/http.ts';
import { limitScenarios } from './protocol/limits.ts';
import { tlsScenarios } from './protocol/tls.ts';
import { redactionScenario } from './postgres/redaction.ts';
import { installSuite } from './support/suite-env.ts';

installSuite('protocol');
unitScenarios();
httpScenarios();
limitScenarios();
tlsScenarios();
redactionScenario();
