package org.zipp.ai.domain.material.model.valobj;

public record MaterialVectorLocation(String indexName, String namespace, String vectorId) {
    public MaterialVectorLocation {
        indexName = required(indexName, "indexName");
        namespace = required(namespace, "namespace");
        vectorId = required(vectorId, "vectorId");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
