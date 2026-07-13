-- Case Health is version-specific; changing one published version must not overwrite another.
ALTER TABLE eval_case_health
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (case_id, case_version);
