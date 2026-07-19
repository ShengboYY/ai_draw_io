package org.zipp.ai.ingestion.worker.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.zipp.ai.domain.ingestion.model.valobj.CanonicalPage;
import org.zipp.ai.domain.ingestion.model.valobj.DocumentStructure;
import org.zipp.ai.domain.ingestion.model.valobj.PageExtraction;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Versioned gzip JSON codec for immutable page artifacts. */
public final class RevisionPageCodec {

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

    public PageExtraction decodeExtraction(byte[] content) {
        return decodeValue(content, PageExtraction.class);
    }

    public CanonicalPage decodeCanonicalPage(byte[] content) {
        return decodeValue(content, CanonicalPage.class);
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

    private <T> T decodeValue(byte[] content, Class<T> type) {
        try (GZIPInputStream gzip = new GZIPInputStream(
                new ByteArrayInputStream(Objects.requireNonNull(content, "content")))) {
            return mapper.readValue(gzip, type);
        } catch (IOException e) {
            throw new IllegalStateException("page artifact decoding failed", e);
        }
    }
}
