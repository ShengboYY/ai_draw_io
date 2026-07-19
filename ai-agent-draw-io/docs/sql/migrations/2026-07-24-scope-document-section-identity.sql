-- WP3C-B: the stable structure section reference is unique inside a processing revision.
ALTER TABLE material_section
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (revision_id, id);
