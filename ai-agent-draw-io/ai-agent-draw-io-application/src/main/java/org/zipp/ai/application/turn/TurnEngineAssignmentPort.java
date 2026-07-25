package org.zipp.ai.application.turn;

/** Durable sticky assignment seam; implementation locks the migration singleton row. */
public interface TurnEngineAssignmentPort {

    AdmissionWriteOutcome assignOrReuse(TurnEngineAssignmentCommand command);
}
