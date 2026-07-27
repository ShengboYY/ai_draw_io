package org.zipp.ai.infrastructure.adapter.port;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HmacAnonymousWorkspaceCredentialHasherTest {

    private static final String PEPPER = "test-pepper-with-at-least-thirty-two-characters";

    @Test
    void shouldCreateDeterministicNonReversibleCredentialHash() {
        HmacAnonymousWorkspaceCredentialHasher hasher = new HmacAnonymousWorkspaceCredentialHasher(PEPPER);

        String first = hasher.hash("secret-value-with-adequate-entropy-000001");
        String second = hasher.hash("secret-value-with-adequate-entropy-000001");

        assertEquals(first, second);
        assertFalse(first.contains("secret-value"));
        assertTrue(hasher.matches("secret-value-with-adequate-entropy-000001", first));
        assertFalse(hasher.matches("secret-value-with-adequate-entropy-000002", first));
    }

    @Test
    void shouldBindHashesToTheConfiguredServerPepper() {
        HmacAnonymousWorkspaceCredentialHasher first = new HmacAnonymousWorkspaceCredentialHasher(PEPPER);
        HmacAnonymousWorkspaceCredentialHasher second = new HmacAnonymousWorkspaceCredentialHasher(
                "different-test-pepper-with-at-least-thirty-two-characters");

        assertNotEquals(first.hash("secret-value-with-adequate-entropy-000001"),
                second.hash("secret-value-with-adequate-entropy-000001"));
    }
}
