package org.zipp.ai.ingestion.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;
import org.zipp.ai.infrastructure.adapter.s3.S3OriginalPromotionAdapter;
import org.zipp.ai.infrastructure.adapter.s3.S3PinnedQuarantineContentAdapter;
import org.zipp.ai.infrastructure.adapter.s3.S3RevisionArtifactAdapter;
import org.zipp.ai.ingestion.worker.document.PdfBoxDocumentParser;
import org.zipp.ai.ingestion.worker.document.RevisionPageCodec;
import org.zipp.ai.ingestion.worker.document.TesseractOcrEngine;
import org.zipp.ai.ingestion.worker.document.TesseractInstallationVerifier;
import org.zipp.ai.ingestion.worker.document.DocumentProcessingProfile;
import org.zipp.ai.ingestion.worker.security.ClamAvScannerAdapter;
import org.zipp.ai.ingestion.worker.security.SecureFileValidator;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class WorkerConfig {

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
    public DocumentProcessingProfile documentProcessingProfile(
            @Value("${worker.document.render-dpi:200}") int renderDpi,
            @Value("${worker.tesseract.executable:tesseract}") String executable,
            @Value("${worker.tesseract.languages:eng+chi_sim}") String languages,
            @Value("${worker.tesseract.timeout-seconds:120}") long timeoutSeconds,
            @Value("${worker.tesseract.runtime-version}") String runtimeVersion) {
        OcrSelectionPolicy selection = new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03);
        CanonicalPageAssembler canonical = new CanonicalPageAssembler(0.70);
        return DocumentProcessingProfile.of(renderDpi, executable, languages, timeoutSeconds,
                runtimeVersion, selection, canonical);
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
            ObjectMapper objectMapper, DocumentProcessingProfile processingProfile) {
        OcrSelectionPolicy selection = new OcrSelectionPolicy(40, 0.10, 0.20, 0.01);
        CanonicalPageAssembler assembler = new CanonicalPageAssembler(0.70);
        RevisionPageCodec codec = new RevisionPageCodec(objectMapper);
        return new DocumentProcessingJobHandler(work, artifacts, parser, ocr, selection,
                assembler, codec, processingProfile, queue, clock);
    }

    @Bean
    public WorkerPoller workerPoller(ProcessingQueuePort queue,
                                     SecureUploadJobHandler secureUploadHandler,
                                     ObjectProvider<MaterializationJobHandler> materializationHandler,
                                     ObjectProvider<DocumentProcessingJobHandler> documentProcessingHandler,
                                     Clock clock, DocumentProcessingProfile processingProfile,
                                     @Value("${worker.id}") String workerId,
                                     @Value("${worker.materialization-enabled:false}") boolean materializationEnabled,
                                     @Value("${worker.document-processing-enabled:false}")
                                     boolean documentProcessingEnabled) {
        return new WorkerPoller(queue, secureUploadHandler, materializationHandler.getIfAvailable(),
                documentProcessingHandler.getIfAvailable(), clock, workerId, materializationEnabled,
                documentProcessingEnabled, processingProfile.overallFingerprint());
    }
}
