package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Protects the production telemetry shape used by the read-only Finding projection. */
public class TraceFindingMapperContractTest {

    @Test
    public void shouldProjectRouteFromRoutingEventWithoutUsingRequestType() throws Exception {
        String mapper = resource("mybatis/mapper/trace_to_eval_mapper.xml");

        assertTrue(mapper.contains("event_type='ROUTING_DECIDED'"));
        assertTrue(mapper.contains("'$.routeType'"));
        assertFalse(mapper.contains("r.request_type=#{routeType}"));
    }

    @Test
    public void shouldJoinOnlyTheLatestReviewInTheFindingQuery() throws Exception {
        String mapper = resource("mybatis/mapper/trace_to_eval_mapper.xml");

        assertTrue(mapper.contains("left join eval_case_review rv"));
        assertTrue(mapper.contains("order by er.reviewed_at desc,er.id desc limit 1"));
    }

    private String resource(String path) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
