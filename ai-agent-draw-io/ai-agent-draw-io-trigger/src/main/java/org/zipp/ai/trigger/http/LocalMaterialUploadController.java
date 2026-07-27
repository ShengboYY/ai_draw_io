package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionState;
import org.zipp.ai.domain.ingestion.port.QuarantineObjectWriterPort;
import org.zipp.ai.domain.ingestion.port.UploadSessionStore;

import java.io.IOException;
import java.time.Clock;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.GONE;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/v1/material-uploads")
@ConditionalOnExpression("${app.material-upload.enabled:false}"
        + " and '${app.material-upload.storage:local}' == 'local'")
public class LocalMaterialUploadController {

    private final CurrentOwnerHttpResolver ownerResolver;
    private final UploadSessionStore sessionStore;
    private final QuarantineObjectWriterPort objectWriter;
    private final Clock clock;

    public LocalMaterialUploadController(CurrentOwnerHttpResolver ownerResolver,
                                         UploadSessionStore sessionStore,
                                         QuarantineObjectWriterPort objectWriter,
                                         Clock clock) {
        this.ownerResolver = ownerResolver;
        this.sessionStore = sessionStore;
        this.objectWriter = objectWriter;
        this.clock = clock;
    }

    @PostMapping(path = "/{uploadId}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> upload(@PathVariable String uploadId, HttpServletRequest request)
            throws IOException {
        ResolvedOwner owner = ownerResolver.resolve(null)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED));
        UploadSession session = sessionStore.findByIdForOwner(
                        uploadId, owner.getOwnerType(), owner.getOwnerId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        if (session.state() != UploadSessionState.CREATED) {
            throw new ResponseStatusException(CONFLICT, "upload is no longer accepting bytes");
        }
        if (session.expire(clock.instant())) {
            sessionStore.expire(session);
            throw new ResponseStatusException(GONE, "upload policy expired");
        }
        try {
            // The adapter publishes the file only after the declared byte count is satisfied.
            objectWriter.write(session.quarantineBucket(), session.quarantineKey(),
                    request.getInputStream(), session.expectedSize());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, "uploaded object size is invalid");
        }
        return ResponseEntity.noContent().build();
    }
}
