-- Destroys every relay mailbox row. Only for an isolated/empty database; see docs/operations-v1.md.
DROP TABLE relay_ack;
DROP TRIGGER relay_draft_purge_photo_on_terminal ON relay_draft;
DROP FUNCTION relay_draft_purge_photo();
DROP TABLE relay_draft_photo;
DROP TABLE relay_draft;
DROP TABLE relay_credential;
DROP TABLE relay_pairing;
DROP TABLE relay_configuration;
