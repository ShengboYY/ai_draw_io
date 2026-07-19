-- WP3C-B: every citable text or visual reference keeps the exact S3 object version it was built from.
ALTER TABLE evidence_unit
    ADD COLUMN display_text_object_version_id VARCHAR(255) NULL AFTER display_text_object_key,
    ADD COLUMN visual_object_version_id VARCHAR(255) NULL AFTER visual_object_key,
    ADD COLUMN visual_analysis_object_version_id VARCHAR(255) NULL AFTER visual_analysis_object_key,
    ADD CONSTRAINT chk_evidence_display_pin CHECK (
        (display_text_object_key IS NULL) = (display_text_object_version_id IS NULL)
    ),
    ADD CONSTRAINT chk_evidence_visual_pin CHECK (
        (visual_object_key IS NULL) = (visual_object_version_id IS NULL)
    ),
    ADD CONSTRAINT chk_evidence_analysis_pin CHECK (
        (visual_analysis_object_key IS NULL) = (visual_analysis_object_version_id IS NULL)
    );
