-- Recovery scans status equality, lease range, and lease ordering (LIMIT 20).
-- idx_task_dispatch uses next_retry_at and cannot provide this range/order.
-- Keep one non-covering B-tree; status transitions/lease renewals pay maintenance cost.
CREATE INDEX idx_task_status_lease ON document_index_task (status, lease_until);
