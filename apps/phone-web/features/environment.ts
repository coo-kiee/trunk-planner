// Reads browser capabilities once on the client. A separate file so the judgement itself stays pure.
import type { RelayUrlCheck } from '../shared/config/relay-url.ts';
import { judgeEnvironment, type EnvironmentBlock } from '../shared/security/environment.ts';

export function readEnvironmentBlocks(relay: RelayUrlCheck): EnvironmentBlock[] {
  return judgeEnvironment({
    isSecureContext: window.isSecureContext,
    hasSubtleCrypto: Boolean(globalThis.crypto?.subtle),
    hasIndexedDb: typeof indexedDB !== 'undefined',
    pageProtocol: location.protocol,
    relay,
  });
}
