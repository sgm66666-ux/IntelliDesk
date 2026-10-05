-- Keep the originating request trace across delayed dispatch/retry/recovery.
-- Existing tasks remain nullable and retain backwards-compatible message handling.
ALTER TABLE document_index_task ADD COLUMN trace_id VARCHAR(64);
