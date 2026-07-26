package org.zipp.ai.trigger.http.turn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.trigger.http.CurrentOwnerHttpResolver;

import java.util.Objects;

/**
 * Feature-gated V2 HTTP boundary. It only composes the canonical adapters; the legacy controller
 * and assignment engine remain untouched until the migration gates explicitly enable this route.
 */
@RestController
@RequestMapping("/api/v2/turns")
@ConditionalOnProperty(name = "turn-engine.http.v2.enabled", havingValue = "true")
public final class TurnV2HttpController {

    private final CurrentOwnerHttpResolver ownerResolver;
    private final TurnHttpDeliveryAdapter delivery;
    private final TurnHttpControlAdapter control;

    public TurnV2HttpController(
            CurrentOwnerHttpResolver ownerResolver,
            TurnHttpDeliveryAdapter delivery,
            TurnHttpControlAdapter control
    ) {
        this.ownerResolver = Objects.requireNonNull(ownerResolver, "ownerResolver");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.control = Objects.requireNonNull(control, "control");
    }

    @PostMapping
    public ResponseEntity<TurnHttpDeliveryResult> submit(@RequestBody TurnHttpRequest request) {
        TurnHttpDeliveryResult result = delivery.executeSync(actor(), request);
        return ResponseEntity.status(result.responseStatus()).body(result);
    }

    @GetMapping("/{turnId}/status")
    public ResponseEntity<TurnHttpStatusResult> status(
            @PathVariable String turnId,
            @RequestParam String conversationReference,
            @RequestParam String diagramId
    ) {
        TurnHttpStatusResult result = control.statusResponse(actor(),
                new TurnHttpControlRequest(turnId, conversationReference, diagramId));
        return ResponseEntity.status(result.httpStatus()).body(result);
    }

    @PostMapping("/{turnId}/cancel")
    public ResponseEntity<TurnHttpCancelResult> cancel(
            @PathVariable String turnId,
            @RequestParam String conversationReference,
            @RequestParam String diagramId,
            @RequestBody TurnHttpCancelBody body
    ) {
        Objects.requireNonNull(body, "body");
        TurnHttpCancelResult result = control.cancelResponse(actor(), new TurnHttpCancelRequest(
                new TurnHttpControlRequest(turnId, conversationReference, diagramId), body.reason()));
        return ResponseEntity.status(result.httpStatus()).body(result);
    }

    private AuthenticatedActor actor() {
        ResolvedOwner owner = ownerResolver.resolve(null)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "TURN_AUTH_REQUIRED"));
        // The owner key is the stable cohort input until rollout policy gets its own application port.
        return new AuthenticatedActor(owner.getOwnerId(), owner.getOwnerId());
    }
}
