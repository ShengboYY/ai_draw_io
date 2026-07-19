package org.zipp.ai.domain.chartbook.model.aggregate;

import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookStatus;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Chartbook {

    private final String id;
    private final String ownerKey;
    private final String name;
    private final Set<String> diagramIds = new LinkedHashSet<>();
    private final Set<String> sharedMaterialIds = new LinkedHashSet<>();
    private ChartbookStatus status = ChartbookStatus.ACTIVE;

    private Chartbook(String id, OwnerType ownerType, String ownerKey, String name) {
        if (ownerType != OwnerType.USER) {
            throw new IllegalArgumentException("anonymous owners cannot create chartbooks");
        }
        this.id = requireText(id, "id");
        this.ownerKey = requireText(ownerKey, "ownerKey");
        this.name = requireText(name, "name");
    }

    public static Chartbook create(String id, OwnerType ownerType, String ownerKey, String name) {
        return new Chartbook(id, ownerType, ownerKey, name);
    }

    public void addDiagram(String diagramId) {
        requireActive();
        diagramIds.add(requireText(diagramId, "diagramId"));
    }

    public void removeDiagram(String diagramId) {
        requireActive();
        diagramIds.remove(requireText(diagramId, "diagramId"));
    }

    public void shareMaterial(String materialId, String materialOwnerKey) {
        requireActive();
        if (!ownerKey.equals(requireText(materialOwnerKey, "materialOwnerKey"))) {
            throw new IllegalArgumentException("chartbook cannot share another owner's material");
        }
        sharedMaterialIds.add(requireText(materialId, "materialId"));
    }

    public void removeSharedMaterial(String materialId) {
        requireActive();
        sharedMaterialIds.remove(requireText(materialId, "materialId"));
    }

    public void archive() {
        requireActive();
        status = ChartbookStatus.ARCHIVED;
    }

    private void requireActive() {
        if (status != ChartbookStatus.ACTIVE) {
            throw new IllegalStateException("chartbook is archived");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public String id() { return id; }
    public String ownerKey() { return ownerKey; }
    public String name() { return name; }
    public ChartbookStatus status() { return status; }
    public Set<String> diagramIds() { return Collections.unmodifiableSet(diagramIds); }
    public Set<String> sharedMaterialIds() { return Collections.unmodifiableSet(sharedMaterialIds); }
}
