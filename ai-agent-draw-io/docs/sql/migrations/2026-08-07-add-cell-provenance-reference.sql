-- WP6: persist the opaque provenance identity embedded in draw.io XML.
ALTER TABLE diagram_cell_provenance
    ADD COLUMN provenance_ref VARCHAR(64) NULL AFTER canvas_version;

-- Existing development rows receive a deterministic server-owned identity before NOT NULL is enforced.
UPDATE diagram_cell_provenance
SET provenance_ref = CONCAT('prv_', SUBSTRING(SHA2(CONCAT(diagram_id, '|', canvas_version, '|', cell_id), 256), 1, 24))
WHERE provenance_ref IS NULL;

ALTER TABLE diagram_cell_provenance
    MODIFY COLUMN provenance_ref VARCHAR(64) NOT NULL,
    ADD KEY idx_cell_provenance_ref (diagram_id, provenance_ref);
