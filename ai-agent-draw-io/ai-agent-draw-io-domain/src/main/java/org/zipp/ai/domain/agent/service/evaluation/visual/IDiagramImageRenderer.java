package org.zipp.ai.domain.agent.service.evaluation.visual;

/** Deterministic boundary that turns a draw.io document into provider-ready image bytes. */
public interface IDiagramImageRenderer {
    RenderedDiagram render(String canvasXml);

    record RenderedDiagram(byte[] bytes, String mimeType, String rendererVersion, int width, int height) {
        public RenderedDiagram {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
