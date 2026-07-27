type LibraryCapabilities = { catalog: string; preview: string; upload?: string };

export const materialCapabilityMessage = (capabilities: LibraryCapabilities) => {
  if (capabilities.catalog !== 'AVAILABLE') {
    return 'The library is currently unavailable. Contact an administrator to enable it.';
  }
  if (capabilities.preview !== 'AVAILABLE') {
    return 'The library is available, but page previews are currently unavailable.';
  }
  if (capabilities.upload && capabilities.upload !== 'AVAILABLE') {
    return 'The library is available, but uploads are currently unavailable.';
  }
  return null;
};

// Owner checks are deliberately not exposed through distinct UI messages.
export const materialAccessMessage = (code: string) => {
  // Intentionally consume the backend code without revealing ownership information.
  void code;
  return 'This item does not exist or you do not have access.';
};

export const materialPreviewUrl = (baseUrl: string, materialId: string, versionId: string, pageNo: number) =>
  `${baseUrl}/materials/${encodeURIComponent(materialId)}/versions/${encodeURIComponent(versionId)}/pages/${pageNo}/preview`;
