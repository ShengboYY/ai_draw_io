export type MaterialUploadTarget = {
  scopeType: 'CONVERSATION' | 'DIAGRAM' | 'CHARTBOOK' | 'LIBRARY';
  scopeId: string;
  retentionClass: 'TEMPORARY' | 'RETAINED';
  diagramId?: string;
};

export type InitiateMaterialUploadRequest = {
  displayName: string;
  mediaType: 'application/pdf' | 'image/png' | 'image/jpeg';
  byteSize: number;
  sha256: string;
  target: MaterialUploadTarget;
  newVersionOfMaterialId?: string;
  batchFileCount?: number;
};

export type BrowserPostPolicy = {
  url: string;
  fields: Record<string, string>;
  expiresAt?: string;
};

export type MaterialUploadStatus = {
  uploadId: string;
  state: string;
  postPolicy?: BrowserPostPolicy;
  pinnedObjectVersionId?: string;
  materialId?: string;
  versionId?: string;
  errorCode?: string;
};

export type MaterialCapabilities = {
  upload: string;
  catalog: string;
  preview: string;
  retrieval: string;
  denseRetrieval: string;
  visualObservation: string;
  directImageConversion: string;
  anonymousUpload: string;
  acceptedMimeTypes: string[];
  maxBatchFiles: number;
};

export type MaterialCatalogCard = {
  materialId: string;
  kind: string;
  displayName: string;
  retentionClass: string;
  lifecycleState: string;
  latestVersionId?: string;
  latestVersionNo?: number;
  processingStatus: string;
  progress: number;
  searchStatus: string;
  pageCount?: number;
  updatedAt: string;
};

export type MaterialCatalogPage = {
  items: MaterialCatalogCard[];
  total: number;
  limit: number;
  offset: number;
};

export type MaterialVersion = {
  versionId: string;
  versionNo: number;
  detectedMime: string;
  byteSize: number;
  pageCount?: number;
  processingStatus: string;
  progress: number;
  createdAt: string;
};

export type MaterialScope = {
  linkId: string;
  scopeType: string;
  scopeKey: string;
};

export type MaterialCatalogDetails = {
  material: MaterialCatalogCard;
  versions: MaterialVersion[];
  scopes: MaterialScope[];
};

export type MaterialPage = {
  pageNo: number;
  width: number;
  height: number;
  nativeTextStatus: string;
  ocrStatus: string;
  ocrQuality?: number;
  visualStatus: string;
  errorCode?: string;
  canonicalAvailable: boolean;
  previewAvailable: boolean;
};

export type MaterialPageSet = {
  materialId: string;
  versionId: string;
  revisionId: string;
  revisionNo: number;
  processingStatus: string;
  progress: number;
  excludedPages: number[];
  pages: MaterialPage[];
};

export type MaterialLifecycleResult = {
  materialId: string;
  retentionClass: string;
  lifecycleState: string;
  lifecycleGeneration: number;
  expiresAt?: string;
  trashExpiresAt?: string;
};

export type MaterialDeletionImpact = {
  materialId: string;
  lifecycleGeneration: number;
  versionCount: number;
  diagramCount: number;
  chartbookCount: number;
  citationCount: number;
  deletionConfirmationToken: string;
  confirmationExpiresAt: string;
};

export type MaterialReprocessResult = {
  materialId: string;
  versionId: string;
  revisionId: string;
  revisionNo: number;
  processingStatus: string;
  reused: boolean;
};

export type Chartbook = {
  chartbookId: string;
  name: string;
  status: string;
  diagramIds: string[];
  materialIds: string[];
  createdAt: string;
  updatedAt: string;
};
