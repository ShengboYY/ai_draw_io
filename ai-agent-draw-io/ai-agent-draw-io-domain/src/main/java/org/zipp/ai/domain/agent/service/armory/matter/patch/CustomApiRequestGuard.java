package org.zipp.ai.domain.agent.service.armory.matter.patch;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

final class CustomApiRequestGuard {

    private final AddressResolver resolver;

    CustomApiRequestGuard() {
        this(InetAddress::getAllByName);
    }

    CustomApiRequestGuard(AddressResolver resolver) {
        this.resolver = resolver == null ? InetAddress::getAllByName : resolver;
    }

    URI rewriteCustomUri(URI originalUri, String customBaseUrl, String customCompletionsPath) {
        try {
            URI customUri = new URI(customBaseUrl);
            String customPath = customUri.getPath();
            if (customPath == null) {
                customPath = "";
            }
            String path = originalUri.getPath();

            if (customCompletionsPath != null && !customCompletionsPath.isEmpty()) {
                String normalizedCustomPath = customCompletionsPath.startsWith("/")
                        ? customCompletionsPath
                        : "/" + customCompletionsPath;
                if (path.endsWith("/chat/completions") || path.endsWith("/embeddings")) {
                    if (path.endsWith("/embeddings")) {
                        if (customPath.endsWith("/v1") && path.startsWith("/v1")) {
                            customPath = customPath.substring(0, customPath.length() - 3);
                        }
                    } else {
                        path = normalizedCustomPath;
                    }
                }
            } else if (customPath.endsWith("/v1") && path.startsWith("/v1")) {
                customPath = customPath.substring(0, customPath.length() - 3);
            }

            String newPath = (customPath + path).replaceAll("//+", "/");
            return new URI(
                    customUri.getScheme(),
                    customUri.getUserInfo(),
                    customUri.getHost(),
                    customUri.getPort(),
                    newPath,
                    originalUri.getQuery(),
                    originalUri.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("custom baseUrl must be a valid HTTPS URL.", e);
        }
    }

    void assertAllowed(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("custom baseUrl must use HTTPS.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getUserInfo() != null || isBlockedHostName(host)) {
            throw new IllegalArgumentException("custom baseUrl host is not allowed.");
        }

        InetAddress[] addresses;
        try {
            // Resolve at request time so DNS answers that point to private or metadata IPs are blocked.
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("custom baseUrl host could not be resolved.", e);
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("custom baseUrl host could not be resolved.");
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("custom baseUrl resolved to a blocked network address.");
            }
        }
    }

    void assertRedirectAllowed(URI requestUri, URI redirectLocation) {
        if (redirectLocation == null) {
            return;
        }
        URI redirectUri = requestUri.resolve(redirectLocation).normalize();
        assertAllowed(redirectUri);
    }

    private boolean isBlockedHostName(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        return host.endsWith(".")
                || host.contains("%")
                || "localhost".equals(host)
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || host.endsWith(".internal");
    }

    private boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes);
        }
        if (bytes.length == 16) {
            byte[] mappedIpv4 = mappedIpv4(bytes);
            if (mappedIpv4 != null) {
                return isBlockedIpv4(mappedIpv4);
            }
            return isBlockedIpv6(bytes);
        }
        return true;
    }

    private boolean isBlockedIpv4(byte[] bytes) {
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        int third = unsigned(bytes[2]);
        return first == 0
                || first == 10
                || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 0 && third == 0)
                || (first == 192 && second == 0 && third == 2)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || first >= 224;
    }

    private boolean isBlockedIpv6(byte[] bytes) {
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        return (first & 0xfe) == 0xfc
                || (first == 0xfe && (second & 0xc0) == 0x80)
                || first == 0xff
                || isDocumentationIpv6(bytes);
    }

    private boolean isDocumentationIpv6(byte[] bytes) {
        return unsigned(bytes[0]) == 0x20
                && unsigned(bytes[1]) == 0x01
                && unsigned(bytes[2]) == 0x0d
                && unsigned(bytes[3]) == 0xb8;
    }

    private byte[] mappedIpv4(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return null;
            }
        }
        if (bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
            return null;
        }
        return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
