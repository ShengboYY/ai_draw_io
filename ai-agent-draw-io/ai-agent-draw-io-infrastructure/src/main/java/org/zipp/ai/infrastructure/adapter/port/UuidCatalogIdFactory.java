package org.zipp.ai.infrastructure.adapter.port;

import org.zipp.ai.domain.material.port.CatalogIdFactory;

import java.util.UUID;

/** Opaque catalog identifier generator; IDs carry no owner or content information. */
public final class UuidCatalogIdFactory implements CatalogIdFactory {
    @Override
    public String nextId(String prefix) {
        if (prefix == null || !prefix.matches("[a-z]{2,8}")) {
            throw new IllegalArgumentException("catalog ID prefix is invalid");
        }
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
