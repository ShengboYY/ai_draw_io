package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.zipp.ai.infrastructure.dao.material.IMaterialOperationsMapper;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlMaterialOperationsSnapshotAdapterWiringTest {
    @Test
    void springHasAnExplicitConstructorInjectionCandidate() throws NoSuchMethodException {
        // Multiple constructors require an explicit injection candidate to avoid no-arg instantiation.
        Constructor<MySqlMaterialOperationsSnapshotAdapter> constructor =
                MySqlMaterialOperationsSnapshotAdapter.class.getConstructor(IMaterialOperationsMapper.class);

        assertTrue(constructor.isAnnotationPresent(Autowired.class));
    }
}
