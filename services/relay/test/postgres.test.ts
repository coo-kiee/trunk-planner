// test:postgres — real PostgreSQL, real relay process, real HTTP. Cases run in declaration order and share one cluster.
import { pairingScenarios } from './postgres/pairing.ts';
import { draftScenarios } from './postgres/drafts.ts';
import { ackScenarios } from './postgres/acks.ts';
import { revokeScenarios } from './postgres/revoke.ts';
import { ttlScenarios } from './postgres/ttl.ts';
import { restartScenarios } from './postgres/restart.ts';
import { migrationScenarios } from './postgres/migration.ts';
import { redactionScenario } from './postgres/redaction.ts';
import { installSuite } from './support/suite-env.ts';

installSuite('postgres');
pairingScenarios();
draftScenarios();
ackScenarios();
revokeScenarios();
ttlScenarios();
restartScenarios();
migrationScenarios();
redactionScenario();
