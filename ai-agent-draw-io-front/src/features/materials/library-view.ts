type LibraryCapabilities = { catalog: string; preview: string; upload?: string };

export const materialCapabilityMessage = (capabilities: LibraryCapabilities) => {
  if (capabilities.catalog !== 'AVAILABLE') {
    return '资料库当前不可用。请联系管理员开启资料库功能。';
  }
  if (capabilities.preview !== 'AVAILABLE') {
    return '资料库可用，但页面预览当前不可用。';
  }
  if (capabilities.upload && capabilities.upload !== 'AVAILABLE') {
    return '资料库可用，但资料上传当前不可用。';
  }
  return null;
};

// Owner checks are deliberately not exposed through distinct UI messages.
export const materialAccessMessage = (code: string) => {
  // Intentionally consume the backend code without revealing ownership information.
  void code;
  return '资料不存在或你无权访问。';
};

export const materialPreviewUrl = (baseUrl: string, materialId: string, versionId: string, pageNo: number) =>
  `${baseUrl}/materials/${encodeURIComponent(materialId)}/versions/${encodeURIComponent(versionId)}/pages/${pageNo}/preview`;
