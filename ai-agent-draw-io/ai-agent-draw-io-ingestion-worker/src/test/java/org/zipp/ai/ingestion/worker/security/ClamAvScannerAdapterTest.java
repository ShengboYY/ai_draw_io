package org.zipp.ai.ingestion.worker.security;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClamAvScannerAdapterTest {

    @Test
    void streamsBoundedFramesAndClassifiesFoundReply() throws Exception {
        byte[] content = "test-content".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        Path path = Files.createTempFile("clamav-test-", ".bin");
        Files.write(path, content);

        ClamAvScannerAdapter.writeInstream(wire, path);

        assertArrayEquals(content, receive(wire.toByteArray()));
        assertFalse(ClamAvScannerAdapter.classifyReply("stream: Eicar-Signature FOUND").clean());
    }

    private static byte[] receive(byte[] wire) {
        try {
            DataInputStream input = new DataInputStream(new java.io.ByteArrayInputStream(wire));
            byte[] command = input.readNBytes("zINSTREAM\0".length());
            if (!"zINSTREAM\0".equals(new String(command, StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("unexpected command");
            }
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            int length;
            while ((length = input.readInt()) != 0) {
                content.write(input.readNBytes(length));
            }
            return content.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
