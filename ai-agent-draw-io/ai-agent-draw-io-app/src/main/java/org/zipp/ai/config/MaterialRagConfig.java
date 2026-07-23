package org.zipp.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.canvas.CanvasMutationGate;
import org.zipp.ai.domain.citation.port.ClaimSupportVerifierPort;
import org.zipp.ai.domain.citation.answer.EvidenceAnswerCommitPort;
import org.zipp.ai.domain.citation.answer.EvidenceAnswerGeneratorPort;
import org.zipp.ai.domain.citation.answer.EvidenceAnswerGuard;
import org.zipp.ai.domain.citation.answer.EvidenceAnswerService;
import org.zipp.ai.domain.citation.port.CitationQueryPort;
import org.zipp.ai.domain.citation.port.ManualProvenancePort;
import org.zipp.ai.domain.citation.service.CitationGuard;
import org.zipp.ai.domain.citation.service.CitationQueryService;
import org.zipp.ai.domain.citation.service.ManualCitationReconciler;
import org.zipp.ai.domain.grounding.CanvasCommitModule;
import org.zipp.ai.domain.grounding.EvidencePromptAssembler;
import org.zipp.ai.domain.grounding.port.GroundedCanvasCommitPort;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.material.service.MaterialReadLeaseService;
import org.zipp.ai.domain.multimodal.*;
import org.zipp.ai.domain.retrieval.*;
import org.zipp.ai.domain.retrieval.internal.DefaultEvidencePreparationModule;
import org.zipp.ai.domain.retrieval.internal.DefaultRequestProbeService;
import org.zipp.ai.domain.retrieval.internal.DefaultRequestSourceResolutionService;
import org.zipp.ai.domain.retrieval.internal.DeadlineRequestProbeService;
import org.zipp.ai.domain.retrieval.port.*;
import org.zipp.ai.infrastructure.adapter.repository.*;
import org.zipp.ai.infrastructure.adapter.s3.S3EvidenceBlobStoreAdapter;
import org.zipp.ai.infrastructure.adapter.s3.S3RevisionArtifactAdapter;
import org.zipp.ai.infrastructure.adapter.vector.*;
import org.zipp.ai.infrastructure.dao.retrieval.IOnlineRetrievalMapper;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** WP5 online RAG wiring; disabled by default so ordinary text drawing remains independent. */
@Configuration
@ConditionalOnExpression("'${app.material-lifecycle.enabled:false}' == 'true' and "
        + "('${app.material-rag.enabled:false}' == 'true' or "
        + "'${app.material-operations.retrieval-shadow-enabled:false}' == 'true')")
public class MaterialRagConfig {
    @Bean
    public CitationQueryService citationQueryService(CitationQueryPort queryPort) {
        return new CitationQueryService(queryPort);
    }

    @Bean
    public ManualCitationReconciler manualCitationReconciler(ManualProvenancePort provenancePort) {
        return new ManualCitationReconciler(provenancePort);
    }

    @Bean
    public EvidencePromptAssembler evidencePromptAssembler() {
        return new EvidencePromptAssembler();
    }

    @Bean
    @ConditionalOnProperty(name = "app.material-rag.citation-commit-enabled", havingValue = "true")
    @ConditionalOnMissingBean(CitationGuard.class)
    public CitationGuard groundedCitationGuard(ObjectProvider<ClaimSupportVerifierPort> verifiers) {
        // Exact extractive statements remain locally verifiable. Synthesized statements fail closed
        // until the configured-model verifier adapter is present.
        return new CitationGuard(verifiers.getIfAvailable(() -> requests -> java.util.List.of()));
    }

    @Bean
    @ConditionalOnProperty(name = "app.material-rag.citation-commit-enabled", havingValue = "true")
    @ConditionalOnMissingBean(CanvasCommitModule.class)
    public CanvasCommitModule canvasCommitModule(CanvasMutationGate mutationGate,
                                                 CitationGuard groundedCitationGuard,
                                                 GroundedCanvasCommitPort commitPort) {
        return new CanvasCommitModule(mutationGate, groundedCitationGuard, commitPort);
    }

    @Bean
    @ConditionalOnProperty(name = "app.material-rag.evidence-answer-enabled", havingValue = "true")
    public EvidenceAnswerGuard evidenceAnswerGuard(ObjectProvider<ClaimSupportVerifierPort> verifiers) {
        return new EvidenceAnswerGuard(verifiers.getIfAvailable(() -> requests -> java.util.List.of()));
    }

    @Bean
    @ConditionalOnProperty(name = "app.material-rag.evidence-answer-enabled", havingValue = "true")
    public EvidenceAnswerService evidenceAnswerService(EvidenceAnswerGeneratorPort generator,
                                                       EvidenceAnswerGuard guard,
                                                       EvidenceAnswerCommitPort commitPort) {
        return new EvidenceAnswerService(generator, guard, commitPort);
    }

    @Bean
    public RequestProbeDataPort requestProbeDataPort(IOnlineRetrievalMapper retrieval, ICanvasStateStore canvases) {
        return new OnlineRequestProbeAdapter(retrieval, canvases);
    }

    @Bean
    public RequestProbeService requestProbeService(
            RequestProbeDataPort data,
            @Qualifier("materialRagIoExecutor") ExecutorService executor,
            @Value("${app.material-rag.probe-timeout-ms:300}") long timeoutMs) {
        return new DeadlineRequestProbeService(new DefaultRequestProbeService(data), executor,
                Duration.ofMillis(timeoutMs));
    }

    @Bean
    @ConditionalOnMissingBean(RequestSourceResolutionService.class)
    public RequestSourceResolutionService requestSourceResolutionService(
            RequestSourceResolutionPort catalog,
            RequestSourceSnapshotStore snapshots) {
        return new DefaultRequestSourceResolutionService(catalog, snapshots);
    }

    @Bean
    public ServerCanvasPort serverCanvasPort(ICanvasStateStore canvases) {
        return new ServerCanvasRetrievalAdapter(canvases);
    }

    @Bean
    @ConditionalOnMissingBean(EvidenceReadLeaseCoordinator.class)
    public EvidenceReadLeaseCoordinator evidenceReadLeaseCoordinator(MaterialReadLeaseService leases) {
        return new MaterialEvidenceReadLeaseCoordinator(leases);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService materialRagIoExecutor(@Value("${app.material-rag.parallelism:4}") int parallelism) {
        return Executors.newFixedThreadPool(Math.max(3, Math.min(8, parallelism)));
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService materialRagOrchestrationExecutor(
            @Value("${app.material-rag.parallelism:4}") int parallelism) {
        // Orchestration must not occupy the same bounded pool as the I/O tasks it awaits.
        return Executors.newFixedThreadPool(Math.max(2, Math.min(8, parallelism)));
    }

    @Bean
    public S3Client materialRagS3Client(@Value("${app.material-rag.aws-region}") String region) {
        return S3Client.builder().credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(region)).build();
    }

    @Bean
    public RevisionArtifactPort materialRagRevisionArtifactPort(
            @Qualifier("materialRagS3Client") S3Client s3,
            @Value("${app.material-rag.materials-bucket}") String bucket) {
        return new S3RevisionArtifactAdapter(s3, bucket);
    }

    @Bean
    public EvidenceBlobStore evidenceBlobStore(
            @Qualifier("materialRagRevisionArtifactPort") RevisionArtifactPort artifacts) {
        return new S3EvidenceBlobStoreAdapter(artifacts);
    }

    @Bean
    public EvidencePreparationModule evidencePreparationModule(EvidenceCatalog catalog,
                                                               RetrievalLexicalIndex lexical,
                                                               ObjectProvider<EmbeddingPort> embeddings,
                                                               ObjectProvider<RetrievalVectorIndex> vectors,
                                                               ObjectProvider<VisualObservationModule> visualObservations,
                                                               ObjectProvider<TenantKeyPort> tenantKeys,
                                                               EvidenceReadLeaseCoordinator leases,
                                                               EvidenceBlobStore blobs,
                                                               ServerCanvasPort canvases,
                                                               @Qualifier("materialRagOrchestrationExecutor") ExecutorService orchestrationExecutor,
                                                               @Qualifier("materialRagIoExecutor") ExecutorService ioExecutor,
                                                               ObjectProvider<MaterialRetrievalTelemetry> telemetry,
                                                               @Value("${app.material-rag.retrieval-timeout-ms:3000}") long retrievalTimeoutMs,
                                                               @Value("${app.material-rag.hydration-timeout-ms:800}") long hydrationTimeoutMs,
                                                               @Value("${app.material-visual-observation.timeout-ms:30000}") long visualTimeoutMs) {
        TenantKeyPort tenantKey = tenantKeys.getIfAvailable(() -> (ownerType, ownerKey) -> {
            throw new IllegalStateException("dense retrieval is disabled");
        });
        return new DefaultEvidencePreparationModule(catalog, lexical,
                Optional.ofNullable(embeddings.getIfAvailable()), Optional.ofNullable(vectors.getIfAvailable()),
                tenantKey, leases, blobs, canvases, orchestrationExecutor, ioExecutor,
                Duration.ofMillis(retrievalTimeoutMs), Duration.ofMillis(hydrationTimeoutMs),
                telemetry.getIfAvailable(() -> MaterialRetrievalTelemetry.NOOP),
                Optional.ofNullable(visualObservations.getIfAvailable()),
                Duration.ofMillis(visualTimeoutMs));
    }

    @Configuration
    @ConditionalOnProperty(name = "app.material-rag.dense-enabled", havingValue = "true")
    static class PineconeOnlineConfig {
        @Bean
        public PineconeVectorClient materialRagPineconeClient(
                @Value("${app.material-rag.pinecone.api-key}") String apiKey,
                @Value("${app.material-rag.pinecone.index-host}") String indexHost,
                @Value("${app.material-rag.pinecone.embedding-model}") String model,
                @Value("${app.material-rag.pinecone.dimension:1024}") int dimension,
                ObjectMapper objectMapper) {
            return new PineconeVectorClient(apiKey, indexHost, model, dimension, objectMapper);
        }

        @Bean
        @ConditionalOnMissingBean
        public EmbeddingPort materialRagEmbeddingPort(PineconeVectorClient client) {
            return new PineconeEmbeddingAdapter(client);
        }

        @Bean
        @ConditionalOnMissingBean
        public RetrievalVectorIndex materialRagVectorIndex(PineconeVectorClient client,
                @Value("${app.material-rag.pinecone.namespace:prod}") String namespace) {
            return new PineconeRetrievalVectorIndexAdapter(client, namespace);
        }

        @Bean
        @ConditionalOnMissingBean
        public TenantKeyPort materialRagTenantKeyPort(
                @Value("${app.material-rag.pinecone.tenant-hmac-secret}") String secret) {
            return new HmacTenantKeyAdapter(secret);
        }
    }
}
