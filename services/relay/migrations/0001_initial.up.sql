-- Relay store. The relay is a short-lived mailbox; the vehicle Room DB stays the only scene/item authority.
-- Timestamps are always stamped from the database clock so expiry never depends on the Node host clock.

CREATE TABLE relay_configuration (
  version integer PRIMARY KEY CHECK (version > 0),
  config_sha256 char(64) NOT NULL CHECK (config_sha256 ~ '^[0-9a-f]{64}$'),
  contracts_revision integer NOT NULL CHECK (contracts_revision > 0),
  profile text NOT NULL CHECK (profile IN ('production', 'test')),
  pairing_ttl_seconds integer NOT NULL CHECK (pairing_ttl_seconds > 0),
  draft_ttl_seconds integer NOT NULL CHECK (draft_ttl_seconds > 0),
  ack_ttl_seconds integer NOT NULL CHECK (ack_ttl_seconds > 0),
  credential_ttl_seconds integer NOT NULL CHECK (credential_ttl_seconds > 0),
  limits jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE relay_pairing (
  pairing_id uuid PRIMARY KEY,
  configuration_version integer NOT NULL REFERENCES relay_configuration (version),
  created_at timestamptz NOT NULL,
  claim_expires_at timestamptz NOT NULL,
  claimed_at timestamptz,
  -- Non-extending: before claim equals claim_expires_at, at claim becomes claim time + credential TTL.
  credential_expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  CHECK (claim_expires_at > created_at),
  CHECK (credential_expires_at >= claim_expires_at OR claimed_at IS NOT NULL)
);
CREATE INDEX relay_pairing_credential_expiry ON relay_pairing (credential_expires_at);

-- Only SHA-256 digests of the 256-bit opaque bearer tokens are ever stored.
CREATE TABLE relay_credential (
  token_digest bytea PRIMARY KEY CHECK (octet_length(token_digest) = 32),
  pairing_id uuid NOT NULL REFERENCES relay_pairing (pairing_id) ON DELETE CASCADE,
  role text NOT NULL CHECK (role IN ('PAIRING', 'VEHICLE', 'PHONE')),
  consumed_at timestamptz,
  created_at timestamptz NOT NULL,
  UNIQUE (pairing_id, role),
  CHECK (consumed_at IS NULL OR role = 'PAIRING')
);

CREATE TABLE relay_draft (
  draft_pk bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  pairing_id uuid NOT NULL REFERENCES relay_pairing (pairing_id) ON DELETE CASCADE,
  draft_id uuid NOT NULL,
  client_revision bigint NOT NULL CHECK (client_revision BETWEEN 1 AND 9007199254740991),
  payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
  state text NOT NULL CHECK (state IN ('PENDING', 'CONFLICT_REPORTED', 'ACCEPTED', 'REJECTED', 'SUPERSEDED', 'EXPIRED', 'REVOKED')),
  payload jsonb,
  payload_bytes integer NOT NULL CHECK (payload_bytes >= 0),
  consent_version text NOT NULL,
  dimensions_consent boolean NOT NULL CHECK (dimensions_consent),
  photo_consent boolean NOT NULL,
  received_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  terminal_at timestamptz,
  UNIQUE (pairing_id, draft_id, client_revision),
  -- The data-minimisation rule is a constraint: only active drafts may hold item content.
  CONSTRAINT relay_draft_payload_only_while_active
    CHECK ((state IN ('PENDING', 'CONFLICT_REPORTED')) = (payload IS NOT NULL)),
  CONSTRAINT relay_draft_terminal_stamp
    CHECK ((state IN ('PENDING', 'CONFLICT_REPORTED')) = (terminal_at IS NULL))
);
CREATE INDEX relay_draft_active_expiry ON relay_draft (expires_at) WHERE state IN ('PENDING', 'CONFLICT_REPORTED');
CREATE INDEX relay_draft_pairing_active ON relay_draft (pairing_id, draft_pk) WHERE state IN ('PENDING', 'CONFLICT_REPORTED');

CREATE TABLE relay_draft_photo (
  draft_pk bigint PRIMARY KEY REFERENCES relay_draft (draft_pk) ON DELETE CASCADE,
  mime text NOT NULL CHECK (mime IN ('image/jpeg', 'image/webp')),
  bytes bytea NOT NULL CHECK (octet_length(bytes) > 0),
  sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  width integer NOT NULL CHECK (width > 0),
  height integer NOT NULL CHECK (height > 0)
);

-- Every path that makes a draft non-active loses its photo in the same transaction.
CREATE FUNCTION relay_draft_purge_photo() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  DELETE FROM relay_draft_photo WHERE draft_pk = NEW.draft_pk;
  RETURN NULL;
END;
$$;
CREATE TRIGGER relay_draft_purge_photo_on_terminal
  AFTER UPDATE OF state ON relay_draft
  FOR EACH ROW WHEN (NEW.payload IS NULL)
  EXECUTE FUNCTION relay_draft_purge_photo();

CREATE TABLE relay_ack (
  draft_pk bigint NOT NULL REFERENCES relay_draft (draft_pk) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('CONFLICT', 'TERMINAL')),
  ack_sha256 char(64) NOT NULL CHECK (ack_sha256 ~ '^[0-9a-f]{64}$'),
  body jsonb NOT NULL,
  received_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (draft_pk, kind)
);
CREATE INDEX relay_ack_expiry ON relay_ack (expires_at);
