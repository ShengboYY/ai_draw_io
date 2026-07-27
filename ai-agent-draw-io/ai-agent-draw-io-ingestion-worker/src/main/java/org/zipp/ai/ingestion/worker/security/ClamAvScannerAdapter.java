package org.zipp.ai.ingestion.worker.security;

import org.zipp.ai.domain.ingestion.model.valobj.ScanResult;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ClamAvScannerAdapter implements WorkerMalwareScanner {

    private static final byte[] INSTREAM = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final int CHUNK_SIZE = 8192;

    private final String host;
    private final int port;
    private final Duration timeout;

    public ClamAvScannerAdapter(String host, int port, Duration timeout) {
        if (host == null || host.isBlank() || port < 1 || port > 65535 || timeout == null
                || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("valid ClamAV endpoint and timeout are required");
        }
        this.host = host.trim();
        this.port = port;
        this.timeout = timeout;
    }

    @Override
    public ScanResult scan(Path content) {
        try (Socket socket = new Socket()) {
            int timeoutMillis = Math.toIntExact(timeout.toMillis());
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            writeInstream(socket.getOutputStream(), content);
            return classifyReply(readNullTerminated(socket));
        } catch (IOException e) {
            throw new IllegalStateException("ClamAV is unavailable", e);
        }
    }

    static void writeInstream(OutputStream target, Path content) throws IOException {
        DataOutputStream output = new DataOutputStream(target);
        output.write(INSTREAM);
        try (InputStream input = Files.newInputStream(content)) {
            byte[] chunk = new byte[CHUNK_SIZE];
            int length;
            while ((length = input.read(chunk)) != -1) {
                output.writeInt(length);
                output.write(chunk, 0, length);
            }
        }
        output.writeInt(0);
        output.flush();
    }

    static ScanResult classifyReply(String reply) {
        if (reply.endsWith(" OK")) {
            return new ScanResult(true, null);
        }
        if (reply.contains(" FOUND")) {
            return new ScanResult(false, UploadErrorCode.REJECTED_SECURITY.name());
        }
        throw new IllegalStateException("ClamAV returned an indeterminate result");
    }

    private static String readNullTerminated(Socket socket) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int value;
        while ((value = socket.getInputStream().read()) != -1 && value != 0) {
            reply.write(value);
            if (reply.size() > 4096) {
                throw new IOException("ClamAV reply exceeded protocol bound");
            }
        }
        return reply.toString(StandardCharsets.US_ASCII);
    }
}
