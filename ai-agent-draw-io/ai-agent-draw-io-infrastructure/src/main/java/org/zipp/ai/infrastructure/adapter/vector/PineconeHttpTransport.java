package org.zipp.ai.infrastructure.adapter.vector;

import java.net.URI;
import java.util.Map;

/** Small HTTP seam used by the Pinecone contract tests and the JDK adapter. */
interface PineconeHttpTransport {
    PineconeHttpResponse exchange(String method, URI uri, Map<String, String> headers, String body) throws Exception;
}
