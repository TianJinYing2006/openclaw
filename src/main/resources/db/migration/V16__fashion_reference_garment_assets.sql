-- Store the optional, already-cleaned single-garment asset beside the normalized garment facts.
-- This migration does not change the Look/garment relationship and is safe to rebuild from jobs.jsonl.

ALTER TABLE fashion_reference_garments
    ADD COLUMN cutout_asset_id VARCHAR(64) NOT NULL DEFAULT '' AFTER attributes_json,
    ADD COLUMN cutout_asset_version INT NOT NULL DEFAULT 0 AFTER cutout_asset_id,
    ADD COLUMN cutout_asset_media_type VARCHAR(64) NOT NULL DEFAULT 'image/png' AFTER cutout_asset_version,
    ADD COLUMN cutout_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER cutout_asset_media_type,
    ADD COLUMN cutout_source_path VARCHAR(1024) NOT NULL DEFAULT '' AFTER cutout_status,
    ADD COLUMN cutout_sha256 CHAR(64) NOT NULL DEFAULT '' AFTER cutout_source_path,
    ADD COLUMN cutout_model VARCHAR(128) NOT NULL DEFAULT '' AFTER cutout_sha256,
    ADD COLUMN cutout_duration_seconds DECIMAL(10,2) NOT NULL DEFAULT 0.00 AFTER cutout_model,
    ADD COLUMN cutout_error VARCHAR(512) NOT NULL DEFAULT '' AFTER cutout_duration_seconds,
    ADD CONSTRAINT chk_fashion_reference_cutout_status
        CHECK (cutout_status IN ('PENDING', 'READY', 'FAILED', 'SKIPPED')),
    ADD CONSTRAINT chk_fashion_reference_cutout_asset_version CHECK (cutout_asset_version >= 0),
    ADD CONSTRAINT chk_fashion_reference_cutout_duration CHECK (cutout_duration_seconds >= 0);
