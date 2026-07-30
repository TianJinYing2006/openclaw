-- Durable outbox for asynchronous wardrobe vector indexing.
CREATE TABLE IF NOT EXISTS fashion_semantic_index_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    wardrobe_item_id BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL DEFAULT 'UPSERT',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until TIMESTAMP NULL,
    content_hash CHAR(64) NOT NULL DEFAULT '',
    failure_summary VARCHAR(512) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fashion_semantic_job_item (wardrobe_item_id),
    KEY idx_fashion_semantic_job_dispatch (status, next_attempt_at, id),
    CONSTRAINT chk_fashion_semantic_job_operation
        CHECK (operation IN ('UPSERT', 'DELETE')),
    CONSTRAINT chk_fashion_semantic_job_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO fashion_semantic_index_jobs(wardrobe_item_id, operation, status)
SELECT item.id, 'UPSERT', 'PENDING'
FROM fashion_wardrobe_items item
WHERE item.item_status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    operation = 'UPSERT',
    status = IF(fashion_semantic_index_jobs.status = 'SUCCEEDED', 'SUCCEEDED', 'PENDING');
