package org.zipp.ai.domain.multimodal;

import java.util.List;

@FunctionalInterface
public interface VisionModelPort {
    Response observe(Request request);

    record Request(VisualObservationPurpose purpose, String question, List<ImageInput> images,
                   int maximumObservations) {
        public Request { images = List.copyOf(images == null ? List.of() : images); }
    }

    record ImageInput(String evidenceId, String contentType, byte[] bytes) {
        public ImageInput { bytes = bytes == null ? new byte[0] : bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    record Response(List<VerifiedObservation> observations, List<String> gaps) {
        public Response {
            observations = List.copyOf(observations == null ? List.of() : observations);
            gaps = List.copyOf(gaps == null ? List.of() : gaps);
        }
    }
}
