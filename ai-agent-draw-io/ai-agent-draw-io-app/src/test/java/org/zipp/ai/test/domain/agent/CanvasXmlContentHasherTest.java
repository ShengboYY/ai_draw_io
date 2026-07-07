package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.canvas.CanvasXmlContentHasher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class CanvasXmlContentHasherTest {

    @Test
    public void shouldHashCanonicalXmlIndependentOfAttributeOrderAndWhitespace() {
        CanvasXmlContentHasher hasher = new CanvasXmlContentHasher();

        String first = "<mxGraphModel><root><mxCell id=\"2\" value=\"Order\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>";
        String second = "<mxGraphModel>\n  <root>\n    <mxCell parent=\"1\" vertex=\"1\" value=\"Order\" id=\"2\"></mxCell>\n  </root>\n</mxGraphModel>";

        assertEquals(hasher.hash(first), hasher.hash(second));
    }

    @Test
    public void shouldHashDifferentCanvasContentDifferently() {
        CanvasXmlContentHasher hasher = new CanvasXmlContentHasher();

        String first = "<mxGraphModel><root><mxCell id=\"2\" value=\"Old\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>";
        String second = "<mxGraphModel><root><mxCell id=\"2\" value=\"New\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>";

        assertNotEquals(hasher.hash(first), hasher.hash(second));
    }
}
