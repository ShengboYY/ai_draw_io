package org.zipp.ai.test.domain.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import org.zipp.ai.domain.agent.service.chat.ProviderCatalog;
import org.zipp.ai.domain.agent.service.chat.StructuredOutputSchemas;
import org.zipp.ai.domain.agent.service.chat.StructuredOutputTier;
import org.zipp.ai.domain.agent.service.intent.IntentRoutingContract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StructuredOutputContractTest {

    @Test
    public void providerTiersMatchCatalog() {
        assertEquals(StructuredOutputTier.JSON_SCHEMA, ProviderCatalog.tierFor("openai"));
        assertEquals(StructuredOutputTier.JSON_SCHEMA, ProviderCatalog.tierFor("OpenAI")); // case-insensitive
        assertEquals(StructuredOutputTier.JSON_OBJECT, ProviderCatalog.tierFor("deepseek"));
        assertEquals(StructuredOutputTier.NONE, ProviderCatalog.tierFor("custom"));
        assertEquals(StructuredOutputTier.NONE, ProviderCatalog.tierFor("some-unknown-endpoint"));
        assertEquals(StructuredOutputTier.NONE, ProviderCatalog.tierFor(null));
    }

    @Test
    public void downgradeIsScopedToOneCredentialOrEndpoint() {
        // A bad saved credential must not demote every other credential using the same provider.
        String badCredentialScope = "credential:mcr_bad";
        String healthyCredentialScope = "credential:mcr_healthy";

        assertEquals(StructuredOutputTier.JSON_OBJECT, ProviderCatalog.tierFor("moonshot", badCredentialScope));
        assertEquals(StructuredOutputTier.JSON_OBJECT, ProviderCatalog.tierFor("moonshot", healthyCredentialScope));

        ProviderCatalog.downgrade("moonshot", badCredentialScope);

        assertEquals(StructuredOutputTier.NONE, ProviderCatalog.tierFor("moonshot", badCredentialScope));
        assertEquals(StructuredOutputTier.JSON_OBJECT, ProviderCatalog.tierFor("moonshot", healthyCredentialScope));
        assertEquals(StructuredOutputTier.JSON_OBJECT, ProviderCatalog.tierFor("moonshot"));

        ProviderCatalog.downgrade("moonshot", badCredentialScope); // idempotent floor for that scope
        assertEquals(StructuredOutputTier.NONE, ProviderCatalog.tierFor("moonshot", badCredentialScope));
    }

    @Test
    public void routerSchemaIdRegistersSchemaBeforeLookup() {
        String schemaId = IntentRoutingContract.routerSchemaId();
        String schema = StructuredOutputSchemas.get(schemaId);

        assertEquals(IntentRoutingContract.ROUTER_SCHEMA_ID, schemaId);
        assertNotNull("router schema must self-register on class load", schema);
        // Strict-mode requirements + the enum contract are present.
        assertTrue(schema.contains("\"additionalProperties\":false"));
        assertTrue(schema.contains("routeType"));
        assertTrue(schema.contains("create_new"));
        assertTrue(schema.contains("edit_existing"));
        assertFalse(schema.contains("needsCanvasQuality"));
        assertFalse(schema.contains("needsSemanticReview"));
        assertFalse(schema.contains("answerMode"));
        assertFalse("router schema should not expose legacy intent", schema.contains("\"intent\""));
        assertFalse("router schema should not expose legacy drawMode", schema.contains("\"drawMode\""));
        assertFalse("router schema should not expose legacy taskType", schema.contains("\"taskType\""));
        // diagramType uses the prompt-facing alias set, not the canonical downstream set.
        assertTrue(schema.contains("uml_class"));
        assertTrue(schema.contains("concept"));
    }

    @Test
    public void schemaEnumsStayInSyncWithValidationSets() {
        // Same source drives both validation and schema. Preserve order too so schema snapshots,
        // hashes, and diffs stay stable across JVM starts.
        String schema = IntentRoutingContract.routerJsonSchema();
        JSONObject properties = JSON.parseObject(schema).getJSONObject("properties");
        JSONArray routeTypeEnum = properties.getJSONObject("routeType").getJSONArray("enum");

        assertEquals(IntentRoutingContract.ROUTE_TYPES, routeTypeEnum.toJavaList(String.class));
        assertEquals(IntentRoutingContract.CLARIFICATION_NEEDS,
                properties.getJSONObject("clarificationNeed").getJSONArray("enum").toJavaList(String.class));

        for (String routeType : IntentRoutingContract.ROUTE_TYPES) {
            assertTrue("schema missing routeType " + routeType, schema.contains(routeType));
        }
    }
}
