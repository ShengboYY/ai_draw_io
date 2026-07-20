package org.zipp.ai.api.dto;

public record MaterialPageDTO(int pageNo, double width, double height,
                              String nativeTextStatus, String ocrStatus, Double ocrQuality,
                              String visualStatus, String errorCode,
                              boolean canonicalAvailable, boolean previewAvailable) {
}
