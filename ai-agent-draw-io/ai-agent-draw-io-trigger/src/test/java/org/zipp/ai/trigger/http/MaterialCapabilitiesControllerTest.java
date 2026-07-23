package org.zipp.ai.trigger.http;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.operations.MaterialCapabilityService;
import org.zipp.ai.domain.operations.MaterialCapacityBreaker;
import org.zipp.ai.domain.operations.MaterialCapacitySnapshot;
import org.zipp.ai.domain.operations.MaterialFeatureSet;
import org.zipp.ai.domain.operations.MaterialOperationalSnapshot;
import org.zipp.ai.domain.operations.MaterialReleaseApproval;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaterialCapabilitiesControllerTest {

    @Test
    void reportsStablePublicStatesWhenEveryOptionalMaterialFeatureIsDisabled() {
        MaterialCapabilitiesController controller = new MaterialCapabilitiesController(service(),
                MaterialFeatureSet.allDisabled(), new MaterialReleaseApproval(false, ""),
                false, false, false, false, 10);

        var response = controller.capabilities();

        assertEquals("0000", response.getCode());
        assertEquals("DISABLED", response.getData().upload());
        assertEquals("DISABLED", response.getData().catalog());
        assertEquals("DISABLED", response.getData().preview());
        assertEquals("DISABLED", response.getData().visualObservation());
        assertEquals("DISABLED", response.getData().directImageConversion());
        assertEquals(10, response.getData().maxBatchFiles());
    }

    private MaterialCapabilityService service() {
        MaterialOperationalSnapshot operations = new MaterialOperationalSnapshot(Instant.EPOCH,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        return new MaterialCapabilityService(() -> operations,
                new MaterialCapacityBreaker(() -> new MaterialCapacitySnapshot(10, 10, 10, 10, true)));
    }
}
