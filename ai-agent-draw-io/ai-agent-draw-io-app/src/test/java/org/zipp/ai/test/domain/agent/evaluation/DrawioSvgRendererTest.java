package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.evaluation.visual.DrawioSvgRenderer;
import org.zipp.ai.domain.agent.service.evaluation.visual.EphemeralVisualArtifactService;
import org.zipp.ai.domain.agent.service.evaluation.visual.IDiagramImageRenderer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.Assert.*;

public class DrawioSvgRendererTest {
    private static final String XML = "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
            + "<mxCell id=\"a\" value=\"API &amp; Gateway\" style=\"rounded=1;fillColor=#fff2cc;strokeColor=#d6b656;fontSize=8\" vertex=\"1\" parent=\"1\"><mxGeometry x=\"20\" y=\"30\" width=\"140\" height=\"60\" as=\"geometry\"/></mxCell>"
            + "</root></mxGraphModel>";

    @Test
    public void rendersDeterministicProviderReadySvgBytes() {
        IDiagramImageRenderer.RenderedDiagram first = new DrawioSvgRenderer().render(XML);
        IDiagramImageRenderer.RenderedDiagram second = new DrawioSvgRenderer().render(XML);

        assertEquals("image/svg+xml", first.mimeType());
        assertArrayEquals(first.bytes(), second.bytes());
        String svg = new String(first.bytes(), StandardCharsets.UTF_8);
        assertTrue(svg.startsWith("<svg"));
        assertTrue(svg.contains("API &amp; Gateway"));
        assertTrue(svg.contains("font-size=\"8.0\""));
        assertTrue(svg.contains("fill=\"#fff2cc\""));
        assertFalse(svg.contains("mxGraphModel"));
    }

    @Test
    public void productionPixelsRequireApprovalAndExpire() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-13T00:00:00Z"));
        EphemeralVisualArtifactService store = new EphemeralVisualArtifactService(clock);
        IDiagramImageRenderer.RenderedDiagram image = new DrawioSvgRenderer().render(XML);

        assertThrows(SecurityException.class, () -> store.put(EphemeralVisualArtifactService.Source.PRODUCTION,
                "admin-1", false, Duration.ofMinutes(5), image));
        String ref = store.put(EphemeralVisualArtifactService.Source.PRODUCTION, "admin-1", true,
                Duration.ofMinutes(5), image);
        assertTrue(store.read(ref, "admin-1").isPresent());
        assertThrows(SecurityException.class, () -> store.read(ref, "admin-2"));
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        assertEquals(1, store.purgeExpired());
        assertTrue(store.read(ref, "admin-1").isEmpty());
    }

    @Test
    public void rejectsExternalXmlDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> new DrawioSvgRenderer().render("<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><mxGraphModel><root/></mxGraphModel>"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
