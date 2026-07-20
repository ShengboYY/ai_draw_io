package org.zipp.ai.infrastructure.adapter.repository;

interface MaterialDeletionDatabasePurger {
    void purge(String materialId);
}
