-- Published synthetic Cases must not retain a backlink to a production Trace Candidate.
UPDATE eval_case_working_copy
SET candidate_id = NULL
WHERE status = 'PUBLISHED' AND candidate_id IS NOT NULL;
