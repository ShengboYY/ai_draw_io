package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.grounding.port.GroundedRunControlPort;
import org.zipp.ai.infrastructure.dao.grounding.GroundedRunRowPO;
import org.zipp.ai.infrastructure.dao.grounding.IGroundedRunControlMapper;

import java.util.Objects;

@Repository
public class MySqlGroundedRunControlAdapter implements GroundedRunControlPort {
    private final IGroundedRunControlMapper mapper;

    public MySqlGroundedRunControlAdapter(IGroundedRunControlMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public void start(RunIdentity identity) {
        mapper.insertRun(identity);
        GroundedRunRowPO stored = mapper.selectByRequest(identity);
        if (stored == null || !identity.runId().equals(stored.getRunId())
                || !"RUNNING".equals(stored.getState())
                || !Objects.equals(identity.generation(), stored.getGeneration())) {
            throw new IllegalStateException("GROUNDED_REQUEST_ALREADY_CLAIMED");
        }
    }

    @Override
    @Transactional
    public CancelResult cancel(RunIdentity identity) {
        GroundedRunRowPO stored = mapper.selectByRequest(identity);
        if (stored == null || !identity.runId().equals(stored.getRunId())) {
            throw new IllegalStateException("GROUNDED_RUN_NOT_FOUND");
        }
        if ("COMPLETED".equals(stored.getState())) return CancelResult.ALREADY_COMPLETED;
        if ("CANCELLED".equals(stored.getState())) return CancelResult.ALREADY_CANCELLED;
        if (!Objects.equals(identity.generation(), stored.getGeneration()) || mapper.cancelRun(identity) != 1) {
            throw new IllegalStateException("GROUNDED_RUN_GENERATION_CONFLICT");
        }
        return CancelResult.CANCELLED;
    }
}
