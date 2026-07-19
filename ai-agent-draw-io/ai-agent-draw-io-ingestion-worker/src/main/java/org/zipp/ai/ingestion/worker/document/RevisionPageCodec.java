package org.zipp.ai.ingestion.worker.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.zipp.ai.domain.ingestion.model.valobj.CanonicalPage;
import org.zipp.ai.domain.ingestion.model.valobj.DocumentStructure;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceManifest;
import org.zipp.ai.domain.ingestion.model.valobj.PageExtraction;
import org.zipp.ai.domain.ingestion.model.valobj.VisualCropManifest;
import org.zipp.ai.domain.retrieval.projection.RetrievalChunkProjection;
import org.zipp.ai.domain.retrieval.projection.RetrievalParentContext;
import org.zipp.ai.domain.retrieval.projection.RetrievalProjectionManifest;
import org.zipp.ai.domain.retrieval.model.valobj.VectorBatchPayload;
import org.zipp.ai.domain.retrieval.model.valobj.EmbeddingCacheValue;
import org.zipp.ai.domain.retrieval.projection.VectorProjectionManifest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Versioned gzip JSON codec for immutable page artifacts. */
public final class RevisionPageCodec {

    private static final long DEFAULT_MAXIMUM_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;

    private final ObjectMapper mapper;

    public RevisionPageCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public byte[] encode(PageExtraction extraction) {
        return encodeValue(extraction);
    }

    public byte[] encode(CanonicalPage page) {
        return encodeValue(page);
    }

    public byte[] encode(DocumentStructure structure) {
        return encodeValue(structure);
    }

    public byte[] encode(VisualCropManifest manifest) {
        return encodeValue(manifest);
    }

    public byte[] encode(EvidenceManifest manifest) {
        return encodeValue(manifest);
    }

    public byte[] encode(RetrievalProjectionManifest manifest) {
        return encodeValue(manifest);
    }

    public byte[] encode(RetrievalChunkProjection chunk) {
        return encodeValue(chunk);
    }

    public byte[] encode(RetrievalParentContext parentContext) {
        return encodeValue(parentContext);
    }

    public byte[] encode(VectorBatchPayload payload) {
        return encodeValue(payload);
    }

    public byte[] encode(EmbeddingCacheValue embedding) {
        return encodeValue(embedding);
    }

    public byte[] encode(VectorProjectionManifest manifest) {
        return encodeValue(manifest);
    }

    public PageExtraction decodeExtraction(byte[] content) {
        return decodeValue(content, PageExtraction.class, DEFAULT_MAXIMUM_UNCOMPRESSED_BYTES);
    }

    public CanonicalPage decodeCanonicalPage(byte[] content) {
        return decodeCanonicalPage(content, DEFAULT_MAXIMUM_UNCOMPRESSED_BYTES);
    }

    public CanonicalPage decodeCanonicalPage(byte[] content, long maximumUncompressedBytes) {
        return decodeValue(content, CanonicalPage.class, maximumUncompressedBytes);
    }

    public DocumentStructure decodeDocumentStructure(byte[] content) {
        return decodeDocumentStructure(content, DEFAULT_MAXIMUM_UNCOMPRESSED_BYTES);
    }

    public DocumentStructure decodeDocumentStructure(byte[] content, long maximumUncompressedBytes) {
        return decodeValue(content, DocumentStructure.class, maximumUncompressedBytes);
    }

    public VisualCropManifest decodeVisualCropManifest(byte[] content) {
        return decodeVisualCropManifest(content, DEFAULT_MAXIMUM_UNCOMPRESSED_BYTES);
    }

    public VisualCropManifest decodeVisualCropManifest(byte[] content, long maximumUncompressedBytes) {
        return decodeValue(content, VisualCropManifest.class, maximumUncompressedBytes);
    }

    public EvidenceManifest decodeEvidenceManifest(byte[] content, long maximumUncompressedBytes) {
        return decodeValue(content, EvidenceManifest.class, maximumUncompressedBytes);
    }

    public RetrievalProjectionManifest decodeRetrievalProjectionManifest(
            byte[] content, long maximumUncompressedBytes) {
        return decodeValue(content, RetrievalProjectionManifest.class, maximumUncompressedBytes);
    }

    public VectorBatchPayload decodeVectorBatchPayload(byte[] content, long maximumUncompressedBytes) {
        return decodeValue(content, VectorBatchPayload.class, maximumUncompressedBytes);
    }

    public EmbeddingCacheValue decodeEmbeddingCacheValue(byte[] content, long maximumUncompressedBytes) {
        return decodeValue(content, EmbeddingCacheValue.class, maximumUncompressedBytes);
    }

    private byte[] encodeValue(Object value) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                mapper.writeValue(gzip, value);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("page artifact encoding failed", e);
        }
    }

    private <T> T decodeValue(byte[] content, Class<T> type, long maximumUncompressedBytes) {
        if (maximumUncompressedBytes < 1) {
            throw new IllegalArgumentException("maximumUncompressedBytes must be positive");
        }
        try (GZIPInputStream gzip = new GZIPInputStream(
                new ByteArrayInputStream(Objects.requireNonNull(content, "content")));
             InputStream bounded = new BoundedInputStream(gzip, maximumUncompressedBytes)) {
            return mapper.readValue(bounded, type);
        } catch (IOException e) {
            throw new IllegalStateException("page artifact decoding failed", e);
        }
    }

    /** Counts decompressed bytes so a small gzip cannot expand without a hard bound. */
    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximumBytes;
        private long bytesRead;

        private BoundedInputStream(InputStream input, long maximumBytes) {
            super(input);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                recordBytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) {
                recordBytes(count);
            }
            return count;
        }

        private void recordBytes(int count) throws IOException {
            bytesRead += count;
            if (bytesRead > maximumBytes) {
                throw new ProcessingLimitExceededException(
                        "decompressed artifact exceeds its byte budget");
            }
        }
    }
}
