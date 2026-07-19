-- WP3C-B3d: enforce one globally active retrieval generation at the publication boundary.
ALTER TABLE rag_index_generation
    ADD COLUMN active_slot TINYINT GENERATED ALWAYS AS (
        CASE WHEN state = 'ACTIVE' THEN 1 ELSE NULL END
    ) STORED,
    ADD UNIQUE KEY uk_rag_one_active_generation (active_slot);
