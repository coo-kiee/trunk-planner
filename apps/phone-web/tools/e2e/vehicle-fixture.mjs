// Plays the vehicle's side of relay protocol v1 over real HTTP. It is a protocol fixture: there is no vehicle app, Room or scene here.
const call = async (base, method, path, token, json) => {
  const response = await fetch(base + path, {
    method,
    headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...(json === undefined ? {} : { 'content-type': 'application/json' }) },
    ...(json === undefined ? {} : { body: JSON.stringify(json) }),
  });
  const buffer = Buffer.from(await response.arrayBuffer());
  let body = null;
  try { body = JSON.parse(buffer.toString('utf8')); } catch { /* photo bytes */ }
  return { status: response.status, body, buffer, headers: response.headers };
};

export class VehicleFixture {
  static async pair(base) {
    const created = await call(base, 'POST', '/vehicle/v1/pairings');
    if (created.status !== 201) throw new Error(`create pairing failed: ${created.status} ${JSON.stringify(created.body)}`);
    return new VehicleFixture(base, created.body);
  }

  constructor(base, created) {
    this.base = base;
    this.pairingId = created.pairingId;
    this.token = created.vehicleToken;
    this.qrUrl = created.qrUrl;
    this.qrToken = new URL(created.qrUrl).hash.replace('#pairing=', '');
  }

  async drafts() {
    const listed = await call(this.base, 'GET', '/vehicle/v1/drafts', this.token);
    if (listed.status !== 200) throw new Error(`list failed: ${listed.status}`);
    return listed.body.drafts;
  }

  /** Waits for the draft to arrive at the relay, the way a polling vehicle would. */
  async waitForDraft(predicate = () => true, timeoutMs = 20_000) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const found = (await this.drafts()).find(predicate);
      if (found) return found;
      if (Date.now() > deadline) throw new Error('draft did not arrive');
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
  }

  ack(draft, body) {
    return call(this.base, 'POST', `/vehicle/v1/drafts/${draft.draftId}/${draft.clientRevision}/ack`, this.token, { ackVersion: 1, expectedState: draft.state, payloadHash: draft.payloadHash, ...body });
  }
  accept(draft, acceptedRevision, acceptedItemId = draft.draft.payload.id) { return this.ack(draft, { result: 'VEHICLE_ACCEPTED', acceptedItemId, acceptedRevision }); }
  conflict(draft, authoritativeRevision, authoritativeItemId = draft.draft.payload.id) { return this.ack(draft, { result: 'CONFLICT', authoritativeItemId, authoritativeRevision }); }
  reject(draft, rejectCode) { return this.ack(draft, { result: 'VEHICLE_REJECTED', rejectCode }); }
  photo(draft) { return call(this.base, 'GET', `/vehicle/v1/drafts/${draft.draftId}/${draft.clientRevision}/photo`, this.token); }
  revoke() { return call(this.base, 'POST', '/vehicle/v1/pairing/revoke', this.token); }
  status() { return call(this.base, 'GET', '/vehicle/v1/pairing', this.token); }
}
