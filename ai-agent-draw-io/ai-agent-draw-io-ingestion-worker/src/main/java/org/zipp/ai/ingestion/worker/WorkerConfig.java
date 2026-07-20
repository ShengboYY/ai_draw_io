package org.zipp.ai.ingestion.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.ingestion.port.PinnedQuarantineContentPort;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;
import org.zipp.ai.domain.ingestion.port.SecureUploadWorkPort;
import org.zipp.ai.domain.ingestion.port.MaterializationWorkPort;
import org.zipp.ai.domain.ingestion.port.DocumentParserPort;
import org.zipp.ai.domain.ingestion.port.DocumentProcessingWorkPort;
import org.zipp.ai.domain.ingestion.port.OcrEnginePort;
import org.zipp.ai.domain.ingestion.port.OriginalPromotionPort;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.ingestion.service.CanonicalPageAssembler;
import org.zipp.ai.domain.ingestion.service.DocumentStructureBuilder;
import org.zipp.ai.domain.ingestion.service.EvidenceUnitBuilder;
import org.zipp.ai.domain.ingestion.service.VisualCandidateSelectionPolicy;
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;
import org.zipp.ai.domain.retrieval.projection.RetrievalChunkBuilder;
import org.zipp.ai.domain.retrieval.port.EmbeddingPort;
import org.zipp.ai.domain.retrieval.port.EmbeddingCachePort;
import org.zipp.ai.domain.retrieval.port.RetrievalVectorIndex;
import org.zipp.ai.domain.retrieval.service.RevisionPublicationGate;
import org.zipp.ai.domain.retrieval.port.TenantKeyPort;
import org.zipp.ai.domain.retrieval.port.VectorProjectionWorkPort;
import org.zipp.ai.domain.retrieval.port.IndexGenerationCompatibilityPort;
import org.zipp.ai.domain.retrieval.port.IndexProjectionMaintenancePort;
import org.zipp.ai.domain.retrieval.service.IndexGenerationActivationGate;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;
import org.zipp.ai.domain.retrieval.projection.VectorProjectionPlanner;
import org.zipp.ai.domain.material.port.MaterialDeletionObjectPort;
import org.zipp.ai.domain.material.port.MaterialDeletionVectorPort;
import org.zipp.ai.domain.material.port.MaterialDeletionWorkPort;
import org.zipp.ai.infrastructure.adapter.s3.S3OriginalPromotionAdapter;
import org.zipp.ai.infrastructure.adapter.s3.S3PinnedQuarantineContentAdapter;
import org.zipp.ai.infrastructure.adapter.s3.S3RevisionArtifactAdapter;
import org.zipp.ai.infrastructure.adapter.s3.S3MaterialDeletionAdapter;
import org.zipp.ai.infrastructure.adapter.vector.HmacTenantKeyAdapter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeEmbeddingAdapter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeRetrievalVectorIndexAdapter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorClient;
import org.zipp.ai.infrastructure.adapter.vector.PineconeMaterialDeletionAdapter;
import org.zipp.ai.ingestion.worker.document.PdfBoxDocumentParser;
import org.zipp.ai.ingestion.worker.document.RevisionPageCodec;
import org.zipp.ai.ingestion.worker.document.TesseractOcrEngine;
import org.zipp.ai.ingestion.worker.document.VisualCropDeriver;
import org.zipp.ai.ingestion.worker.document.TesseractInstallationVerifier;
import org.zipp.ai.ingestion.worker.document.DocumentProcessingProfile;
import org.zipp.ai.ingestion.worker.document.EvidenceBuildLimits;
import org.zipp.ai.ingestion.worker.document.MultilingualE5TokenCounter;
import org.zipp.ai.ingestion.worker.document.RevisionEmbeddingCacheAdapter;
import org.zipp.ai.ingestion.worker.security.ClamAvScannerAdapter;
import org.zipp.ai.ingestion.worker.security.SecureFileValidator;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Clock;
import java.time.Duration;
import java.nio.file.Path;

@Configuration
public class WorkerConfig {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerConfig.class);

    @Bean
    public Clock workerClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    public S3Client workerS3Client(@Value("${worker.aws-region}") String region) {
        return S3Client.builder().region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create()).build();
    }

    @Bean
    public PinnedQuarantineContentPort pinnedQuarantineContentPort(S3Client s3Client) {
        return new S3PinnedQuarantineContentAdapter(s3Client);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.materialization-enabled", havingValue = "true")
    public OriginalPromotionPort originalPromotionPort(
            S3Client s3Client, @Value("${worker.materials-bucket}") String materialsBucket) {
        return new S3OriginalPromotionAdapter(s3Client, materialsBucket);
    }

    @Bean
    public SecureFileValidator secureFileValidator(@Value("${worker.clamav.host}") String host,
                                                   @Value("${worker.clamav.port:3310}") int port,
                                                   @Value("${worker.clamav.timeout-seconds:60}") long timeoutSeconds) {
        return new SecureFileValidator(new ClamAvScannerAdapter(host, port, Duration.ofSeconds(timeoutSeconds)));
    }

    @Bean
    public SecureUploadJobHandler secureUploadJobHandler(SecureUploadWorkPort uploads,
                                                         PinnedQuarantineContentPort content,
                                                         ProcessingQueuePort queue,
                                                         SecureFileValidator validator,
                                                         Clock clock) {
        return new SecureUploadJobHandler(uploads, content, queue, validator, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.materialization-enabled", havingValue = "true")
    public MaterializationJobHandler materializationJobHandler(MaterializationWorkPort work,
                                                               OriginalPromotionPort promotion,
                                                               PinnedQuarantineContentPort content,
                                                               ProcessingQueuePort queue,
                                                               Clock clock,
                                                               DocumentProcessingProfile processingProfile) {
        return new MaterializationJobHandler(work, promotion, content, queue, clock, processingProfile);
    }

    @Bean
    public DocumentStructureBuilder documentStructureBuilder() {
        return new DocumentStructureBuilder();
    }

    @Bean
    public VisualCandidateSelectionPolicy visualCandidateSelectionPolicy() {
        return new VisualCandidateSelectionPolicy(12, 0.15, 3);
    }

    @Bean
    public VisualCropDeriver visualCropDeriver() {
        return new VisualCropDeriver(25_000_000, 10 * 1024 * 1024);
    }

    @Bean
    public EvidenceUnitBuilder evidenceUnitBuilder() {
        return new EvidenceUnitBuilder();
    }

    @Bean
    public EvidenceBuildLimits evidenceBuildLimits() {
        return new EvidenceBuildLimits(16L * 1024 * 1024, 5_000_000, 500_000);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "worker.document-processing-enabled", havingValue = "true")
    public MultilingualE5TokenCounter multilingualE5TokenCounter(
            @Value("${worker.retrieval.tokenizer-path}") String tokenizerPath,
            @Value("${worker.retrieval.tokenizer-sha256}") String tokenizerSha256) {
        return new MultilingualE5TokenCounter(Path.of(tokenizerPath), tokenizerSha256);
    }

    @Bean
    public DocumentProcessingProfile documentProcessingProfile(
            @Value("${worker.document.render-dpi:200}") int renderDpi,
            @Value("${worker.tesseract.executable:tesseract}") String executable,
            @Value("${worker.tesseract.languages:eng+chi_sim}") String languages,
            @Value("${worker.tesseract.timeout-seconds:120}") long timeoutSeconds,
            @Value("${worker.tesseract.runtime-version}") String runtimeVersion,
            DocumentStructureBuilder structureBuilder,
            VisualCandidateSelectionPolicy visualPolicy,
            VisualCropDeriver visualCropper,
            EvidenceUnitBuilder evidenceBuilder,
            EvidenceBuildLimits evidenceLimits,
            @Value("${worker.retrieval.tokenizer-sha256}") String tokenizerSha256) {
        OcrSelectionPolicy selection = new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03);
        CanonicalPageAssembler canonical = new CanonicalPageAssembler(0.70);
        DocumentProcessingProfile profile = DocumentProcessingProfile.of(
                renderDpi, executable, languages, timeoutSeconds,
                runtimeVersion, selection, canonical, structureBuilder, visualPolicy, visualCropper,
                evidenceBuilder, evidenceLimits, RetrievalChunkBuilder.fingerprintFor(
                        MultilingualE5TokenCounter.fingerprint(tokenizerSha256)));
        // Operators copy this non-secret identity into the online app before enabling reprocessing.
        var audit = profile.revisionProfile();
        LOG.info("Worker processing profile: fingerprint={}, parser={}, cleaner={}, chunk={}, ocr={}, vlm={}",
                audit.fingerprint(), audit.parserVersion(), audit.cleanerVersion(),
                audit.chunkSchemaVersion(), audit.ocrVersion(), audit.vlmSchemaVersion());
        return profile;
    }

    @Bean
    @ConditionalOnProperty(name = "worker.document-processing-enabled", havingValue = "true")
    public RevisionArtifactPort revisionArtifactPort(
            S3Client s3Client, @Value("${worker.materials-bucket}") String materialsBucket) {
        return new S3RevisionArtifactAdapter(s3Client, materialsBucket);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.document-processing-enabled", havingValue = "true")
    public DocumentParserPort documentParserPort(@Value("${worker.document.render-dpi:200}") int renderDpi) {
        return new PdfBoxDocumentParser(renderDpi);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.document-processing-enabled", havingValue = "true")
    public OcrEnginePort ocrEnginePort(@Value("${worker.tesseract.executable:tesseract}") String executable,
                                      @Value("${worker.tesseract.languages:eng+chi_sim}") String languages,
                                      @Value("${worker.tesseract.timeout-seconds:120}") long timeoutSeconds,
                                      @Value("${worker.tesseract.runtime-version}") String runtimeVersion) {
        TesseractInstallationVerifier.verify(executable, languages, runtimeVersion, Duration.ofSeconds(10));
        return new TesseractOcrEngine(executable, languages, Duration.ofSeconds(timeoutSeconds));
    }

    @Bean
    @ConditionalOnProperty(name = "worker.document-processing-enabled", havingValue = "true")
    public DocumentProcessingJobHandler documentProcessingJobHandler(
            DocumentProcessingWorkPort work, RevisionArtifactPort artifacts,
            DocumentParserPort parser, OcrEnginePort ocr, ProcessingQueuePort queue, Clock clock,
            ObjectMapper objectMapper, DocumentProcessingProfile processingProfile,
            DocumentStructureBuilder structureBuilder,
            VisualCandidateSelectionPolicy visualPolicy,
            VisualCropDeriver visualCropper,
            EvidenceUnitBuilder evidenceBuilder,
            EvidenceBuildLimits evidenceLimits,
            MultilingualE5TokenCounter tokenCounter) {
        OcrSelectionPolicy selection = new OcrSelectionPolicy(40, 0.10, 0.20, 0.01);
        CanonicalPageAssembler assembler = new CanonicalPageAssembler(0.70);
        RevisionPageCodec codec = new RevisionPageCodec(objectMapper);
        return new DocumentProcessingJobHandler(work, artifacts, parser, ocr, selection,
                assembler, structureBuilder, visualPolicy, evidenceBuilder, evidenceLimits,
                new RetrievalChunkBuilder(tokenCounter), visualCropper,
                codec, processingProfile, queue, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.vector-projection-enabled", havingValue = "true")
    public PineconeVectorClient pineconeVectorClient(
            @Value("${worker.pinecone.api-key}") String apiKey,
            @Value("${worker.pinecone.index-host}") String indexHost,
            VectorGenerationProfile profile,
            ObjectMapper objectMapper) {
        return new PineconeVectorClient(apiKey, indexHost, profile.embeddingModel(),
                profile.dimension(), objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.vector-projection-enabled", havingValue = "true")
    public EmbeddingPort embeddingPort(PineconeVectorClient client) {
        return new PineconeEmbeddingAdapter(client);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.vector-projection-enabled", havingValue = "true")
    public EmbeddingCachePort embeddingCachePort(
            RevisionArtifactPort artifacts, ObjectMapper objectMapper) {
        return new RevisionEmbeddingCacheAdapter(artifacts, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.vector-projection-enabled", havingValue = "true")
    public RetrievalVectorIndex retrievalVectorIndex(
            PineconeVectorClient client, @Value("${worker.pinecone.namespace:prod}") String namespace) {
        return new PineconeRetrievalVectorIndexAdapter(client, namespace);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.vector-projection-enabled", havingValue = "true")
    public TenantKeyPort tenantKeyPort(@Value("${worker.pinecone.tenant-hmac-secret}") String secret) {
        return new HmacTenantKeyAdapter(secret);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.vector-projection-enabled", havingValue = "true")
    public VectorGenerationProfile vectorGenerationProfile(
            @Value("${worker.pinecone.index-name:drawio-retrieval-v1}") String indexName,
            @Value("${worker.pinecone.namespace:prod}") String namespace,
            @Value("${worker.retrieval.tokenizer-sha256}") String tokenizerSha256) {
        // The profile creates a new immutable generation whenever the embedding contract changes.
        String embeddingFingerprint = VectorGenerationProfile.sha256(
                "pinecone:multilingual-e5-large:dimension=1024:truncate=NONE");
        return new VectorGenerationProfile(indexName, namespace, "multilingual-e5-large",
                embeddingFingerprint, 1024, "cosine", "vector-v1",
                MultilingualE5TokenCounter.fingerprint(tokenizerSha256));
    }

    @Bean
    @ConditionalOnProperty(name = "worker.vector-projection-enabled", havingValue = "true")
    public VectorProjectionPlanner vectorProjectionPlanner() {
        return new VectorProjectionPlanner(96, 2_000_000);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.vector-projection-enabled", havingValue = "true")
    public RevisionPublicationGate revisionPublicationGate() {
        return new RevisionPublicationGate();
    }

    @Bean
    @ConditionalOnProperty(name = "worker.vector-projection-enabled", havingValue = "true")
    public IndexGenerationActivationGate indexGenerationActivationGate() {
        return new IndexGenerationActivationGate();
    }

    @Bean
    @ConditionalOnProperty(name = "worker.material-deletion-enabled", havingValue = "true")
    public MaterialDeletionObjectPort materialDeletionObjectPort(
            S3Client s3Client, @Value("${worker.materials-bucket}") String materialsBucket) {
        return new S3MaterialDeletionAdapter(s3Client, materialsBucket);
    }

    @Bean
    @ConditionalOnProperty(name = {"worker.material-deletion-enabled", "worker.vector-projection-enabled"},
            havingValue = "true")
    public MaterialDeletionVectorPort materialDeletionVectorPort(
            PineconeVectorClient client, @Value("${worker.pinecone.index-name}") String indexName) {
        return new PineconeMaterialDeletionAdapter(client, indexName);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.material-deletion-enabled", havingValue = "true")
    public MaterialDeletionTaskHandler materialDeletionTaskHandler(
            MaterialDeletionWorkPort work,
            ObjectProvider<MaterialDeletionVectorPort> vectors,
            MaterialDeletionObjectPort objects, Clock clock) {
        // If Pinecone is disabled, materials without vectors still delete; vector-bearing tasks retry safely.
        MaterialDeletionVectorPort vectorPort = locations -> {
            if (locations.isEmpty()) return org.zipp.ai.domain.material.model.valobj.MaterialDeletionReceipt.from(0,
                    java.util.List.of());
            MaterialDeletionVectorPort available = vectors.getIfAvailable();
            if (available == null) throw new IllegalStateException("Pinecone deletion profile is unavailable");
            return available.delete(locations);
        };
        return new MaterialDeletionTaskHandler(work, vectorPort, objects, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.material-deletion-enabled", havingValue = "true")
    public MaterialDeletionPoller materialDeletionPoller(
            MaterialDeletionWorkPort work, MaterialDeletionTaskHandler handler, Clock clock,
            @Value("${worker.id}") String workerId) {
        return new MaterialDeletionPoller(work, handler, clock, workerId);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.vector-projection-enabled", havingValue = "true")
    public IndexGenerationCompatibilityCoordinator indexGenerationCompatibilityCoordinator(
            IndexGenerationCompatibilityPort compatibility, IndexGenerationActivationGate activationGate,
            VectorGenerationProfile profile, Clock clock,
            @Value("${worker.generation-sync-batch-size:100}") int batchSize) {
        return new IndexGenerationCompatibilityCoordinator(
                compatibility, activationGate, profile, batchSize, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.vector-projection-enabled", havingValue = "true")
    public IndexProjectionMaintenanceCoordinator indexProjectionMaintenanceCoordinator(
            IndexProjectionMaintenancePort maintenance, RetrievalVectorIndex vectorIndex,
            VectorGenerationProfile profile, Clock clock,
            @Value("${worker.projection-reconciliation-interval-hours:24}") long reconciliationHours,
            @Value("${worker.retired-generation-cleanup-grace-hours:24}") long cleanupGraceHours,
            @Value("${worker.projection-maintenance-batch-size:100}") int batchSize) {
        return new IndexProjectionMaintenanceCoordinator(maintenance, vectorIndex, profile,
                java.time.Duration.ofHours(reconciliationHours),
                java.time.Duration.ofHours(cleanupGraceHours), batchSize, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.vector-projection-enabled", havingValue = "true")
    public VectorProjectionJobHandler vectorProjectionJobHandler(
            VectorProjectionWorkPort work, IndexProjectionMaintenancePort maintenance,
            RevisionArtifactPort artifacts, EmbeddingPort embedding,
            EmbeddingCachePort embeddingCache,
            RetrievalVectorIndex vectorIndex, TenantKeyPort tenantKeys, VectorProjectionPlanner planner,
            RevisionPublicationGate publicationGate,
            ObjectMapper objectMapper, VectorGenerationProfile profile, ProcessingQueuePort queue, Clock clock) {
        return new VectorProjectionJobHandler(work, maintenance, artifacts, embedding, embeddingCache,
                vectorIndex, tenantKeys, planner, publicationGate,
                new RevisionPageCodec(objectMapper), profile, queue, clock);
    }

    @Bean
    public WorkerPoller workerPoller(ProcessingQueuePort queue,
                                     SecureUploadJobHandler secureUploadHandler,
                                     ObjectProvider<MaterializationJobHandler> materializationHandler,
                                     ObjectProvider<DocumentProcessingJobHandler> documentProcessingHandler,
                                     ObjectProvider<VectorProjectionJobHandler> vectorProjectionHandler,
                                     ObjectProvider<VectorGenerationProfile> vectorGenerationProfile,
                                     Clock clock, DocumentProcessingProfile processingProfile,
                                     @Value("${worker.id}") String workerId,
                                     @Value("${worker.materialization-enabled:false}") boolean materializationEnabled,
                                     @Value("${worker.document-processing-enabled:false}")
                                     boolean documentProcessingEnabled,
                                     @Value("${worker.vector-projection-enabled:false}")
                                     boolean vectorProjectionEnabled) {
        return new WorkerPoller(queue, secureUploadHandler, materializationHandler.getIfAvailable(),
                documentProcessingHandler.getIfAvailable(), vectorProjectionHandler.getIfAvailable(),
                clock, workerId, materializationEnabled, documentProcessingEnabled,
                vectorProjectionEnabled, processingProfile.overallFingerprint(),
                vectorProjectionEnabled ? vectorGenerationProfile.getObject().generationId() : null);
    }
}
