package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvaluationProfileVersion;

import java.util.List;
import java.util.Optional;

/** Read-only port for immutable, versioned Evaluation Profile definitions. */
@FunctionalInterface
public interface IEvaluationProfileCatalog {
    List<EvaluationProfileVersion> list();

    default Optional<EvaluationProfileVersion> find(String profileId, String version) {
        return list().stream().filter(profile -> profile.profileId().equals(profileId)
                && profile.version().equals(version)).findFirst();
    }
}
