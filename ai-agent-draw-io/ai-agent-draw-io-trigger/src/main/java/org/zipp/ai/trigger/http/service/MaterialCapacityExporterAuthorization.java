package org.zipp.ai.trigger.http.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Constant-time service authentication for the automated provider billing exporter. */
@Component
public class MaterialCapacityExporterAuthorization {
    private static final String HEADER = "X-Material-Capacity-Token";
    private final String secret;

    public MaterialCapacityExporterAuthorization(
            @Value("${app.material-operations.capacity.exporter-secret:}") String secret) {
        this.secret = secret == null ? "" : secret;
    }

    public boolean authorized(HttpServletRequest request) {
        if (request == null || secret.length() < 32) return false;
        String supplied = request.getHeader(HEADER);
        return supplied != null && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
