package org.zipp.ai.test.trigger.http;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;
import org.zipp.ai.domain.ingestion.port.QuarantineObjectWriterPort;
import org.zipp.ai.domain.ingestion.port.UploadSessionStore;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;
import org.zipp.ai.trigger.http.CurrentOwnerHttpResolver;
import org.zipp.ai.trigger.http.LocalMaterialUploadController;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LocalMaterialUploadControllerTest {

    @Test
    public void authenticatedOwnerCanStoreBytesForCreatedUpload() throws Exception {
        byte[] content = "local material".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UploadSession session = uploadSession(content.length);
        UploadSessionStore store = mock(UploadSessionStore.class);
        when(store.findByIdForOwner("upl_local", OwnerType.USER, "user_server"))
                .thenReturn(Optional.of(session));
        QuarantineObjectWriterPort writer = mock(QuarantineObjectWriterPort.class);
        var controller = new LocalMaterialUploadController(ownerResolver(), store, writer,
                Clock.fixed(Instant.parse("2026-07-24T00:01:00Z"), ZoneOffset.UTC));
        var request = new MockHttpServletRequest();
        request.setContent(content);

        var response = controller.upload("upl_local", request);

        var input = org.mockito.ArgumentCaptor.forClass(InputStream.class);
        verify(writer).write(eq("quarantine"), eq("incoming/owner/upl_local/object"),
                input.capture(), eq((long) content.length));
        assertArrayEquals(content, input.getValue().readAllBytes());
        assertEquals(204, response.getStatusCode().value());
    }

    private static UploadSession uploadSession(long size) {
        Instant createdAt = Instant.parse("2026-07-24T00:00:00Z");
        return UploadSession.create(
                "upl_local", OwnerType.USER, "user_server", "idem-1",
                "diagram.png", "image/png", size, "a".repeat(64),
                new UploadTarget(MaterialScopeType.DIAGRAM, "diagram-1", RetentionClass.RETAINED),
                null, "quarantine", "incoming/owner/upl_local/object",
                createdAt.plusSeconds(600), createdAt);
    }

    private static CurrentOwnerHttpResolver ownerResolver() {
        return new CurrentOwnerHttpResolver() {
            @Override
            public Optional<ResolvedOwner> resolve(String ignored) {
                return Optional.of(ResolvedOwner.authenticated("user_server"));
            }
        };
    }
}
