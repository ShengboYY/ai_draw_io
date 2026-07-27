package org.zipp.ai.infrastructure.adapter.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.material.model.valobj.MaterialVectorLocation;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PineconeMaterialDeletionAdapterTest {

    @Test
    void deletesOnlyItsIndexAndConfirmsAbsenceBeforeReturningProof() {
        List<String> methods = new ArrayList<>();
        PineconeHttpTransport transport = (method, uri, headers, body) -> {
            methods.add(method + " " + uri.getPath());
            if ("GET".equals(method)) return new PineconeHttpResponse(200, "{\"vectors\":{}}");
            return new PineconeHttpResponse(200, "{}", null, "request-1");
        };
        var adapter = new PineconeMaterialDeletionAdapter(client(transport), "current-index");

        var receipt = adapter.delete(List.of(
                new MaterialVectorLocation("current-index", "ns", "vector-1"),
                new MaterialVectorLocation("old-index", "ns", "vector-2")));

        assertEquals(1, receipt.deletedCount());
        assertEquals("current-index", receipt.providerScope());
        assertFalse(receipt.allRequestedHandled());
        assertEquals(List.of("POST /vectors/delete", "GET /vectors/fetch"), methods);
    }

    @Test
    void failsClosedWhenProviderStillReturnsADeletedVector() {
        PineconeHttpTransport transport = (method, uri, headers, body) ->
                "GET".equals(method)
                        ? new PineconeHttpResponse(200, "{\"vectors\":{\"vector-1\":{}}}")
                        : new PineconeHttpResponse(200, "{}", null, "request-1");
        var adapter = new PineconeMaterialDeletionAdapter(client(transport), "current-index");

        assertThrows(IllegalStateException.class, () -> adapter.delete(List.of(
                new MaterialVectorLocation("current-index", "ns", "vector-1"))));
    }

    private PineconeVectorClient client(PineconeHttpTransport transport) {
        return new PineconeVectorClient("key", "https://index.example", "model", 3,
                transport, new ObjectMapper());
    }
}
