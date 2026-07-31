package org.zipp.ai.application.memory;

/**
 * Atomic consolidation boundary. Implementations must deduplicate evidence and lock the item while
 * applying the activation policy.
 */
public interface AutoMemoryObservationStorePort {
    AutoMemoryObservationOutcome observe(
            SanitizedAutoMemoryObservation observation,
            AutoMemoryActivationPolicy activationPolicy
    );
}
