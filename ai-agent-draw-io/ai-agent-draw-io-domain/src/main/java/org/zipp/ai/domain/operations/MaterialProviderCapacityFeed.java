package org.zipp.ai.domain.operations;

/** Read/update seam for the authenticated provider billing exporter. */
public interface MaterialProviderCapacityFeed {
    MaterialProviderCapacitySnapshot current();
    boolean update(MaterialProviderCapacitySnapshot snapshot);
}
