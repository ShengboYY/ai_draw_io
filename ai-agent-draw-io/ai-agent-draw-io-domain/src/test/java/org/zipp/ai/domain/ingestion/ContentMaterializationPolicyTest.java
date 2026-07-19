package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.OwnedContentBlob;
import org.zipp.ai.domain.ingestion.model.valobj.OwnedMaterialVersion;
import org.zipp.ai.domain.ingestion.service.ContentMaterializationPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContentMaterializationPolicyTest {

    @Test
    void ordinaryUploadReusesTheOwnedVersionWithIdenticalContent() {
        var reusable = new OwnedMaterialVersion("mat_existing", "ver_existing", "rev_existing", "blob_existing");

        var decision = new ContentMaterializationPolicy().decide(
                null, new OwnedContentBlob("blob_existing", "AVAILABLE", "original/existing"), reusable, null);

        assertEquals(ContentMaterializationPolicy.Action.REUSE_VERSION, decision.action());
        assertEquals("mat_existing", decision.materialId());
        assertEquals("ver_existing", decision.versionId());
    }

    @Test
    void explicitNewVersionKeepsTheTargetMaterialEvenWhenAnotherMaterialOwnsTheBlob() {
        var blob = new OwnedContentBlob("blob_existing", "AVAILABLE", "original/existing");
        var otherMaterialVersion = new OwnedMaterialVersion(
                "mat_other", "ver_other", "rev_other", "blob_existing");

        var decision = new ContentMaterializationPolicy().decide(
                "mat_target", blob, otherMaterialVersion, null);

        assertEquals(ContentMaterializationPolicy.Action.CREATE_VERSION, decision.action());
        assertEquals("mat_target", decision.materialId());
        assertEquals("blob_existing", decision.contentBlob().id());
    }

    @Test
    void explicitNewVersionIsANoOpWhenTheTargetAlreadyContainsTheBlob() {
        var blob = new OwnedContentBlob("blob_existing", "AVAILABLE", "original/existing");
        var targetVersion = new OwnedMaterialVersion(
                "mat_target", "ver_target", "rev_target", "blob_existing");

        var decision = new ContentMaterializationPolicy().decide(
                "mat_target", blob, targetVersion, targetVersion);

        assertEquals(ContentMaterializationPolicy.Action.REUSE_VERSION, decision.action());
        assertEquals("ver_target", decision.versionId());
    }

    @Test
    void uniqueOrdinaryUploadCreatesANewLogicalMaterial() {
        var decision = new ContentMaterializationPolicy().decide(null, null, null, null);

        assertEquals(ContentMaterializationPolicy.Action.CREATE_MATERIAL, decision.action());
    }
}
