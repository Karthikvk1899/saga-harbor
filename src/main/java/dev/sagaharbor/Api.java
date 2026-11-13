package dev.sagaharbor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sagas")
public class Api {
    private final Workflow workflow;
    private final byte[] key;
    private final boolean production;
    public Api(Workflow workflow, @Value("${app.api-key}") String key, @Value("${app.mode:demo}") String mode) {
        if (!mode.equals("demo") && !mode.equals("production")) throw new IllegalStateException("APP_MODE must be demo or production");
        this.production = mode.equals("production");
        if (!production && (key.isBlank() || key.length() < 24)) throw new IllegalStateException("SAGA_API_KEY must be at least 24 characters in demo mode");
        this.workflow = workflow; this.key = key.getBytes(StandardCharsets.UTF_8);
    }
    public record StartRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,128}") String orderId) {}
    private void authorize(String bearer) {
        if (production) return; // Production requests are authenticated and authorized in SecurityConfig.
        String provided = bearer != null && bearer.startsWith("Bearer ") ? bearer.substring(7) : "";
        if (!MessageDigest.isEqual(key, provided.getBytes(StandardCharsets.UTF_8))) throw new Unauthorized();
    }
    @PostMapping
    public ResponseEntity<Workflow.Started> start(@RequestHeader(value="Authorization",required=false) String bearer,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey, @Valid @RequestBody StartRequest body) {
        authorize(bearer);
        Workflow.Started result = workflow.begin(body.orderId(), idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }
    @GetMapping("/{id}")
    public Map<Object,Object> get(@RequestHeader(value="Authorization",required=false) String bearer, @PathVariable UUID id) {
        authorize(bearer);
        Map<Object,Object> result = workflow.get(id.toString());
        if (result.isEmpty()) throw new Missing();
        return result;
    }
    @ExceptionHandler(Unauthorized.class) ResponseEntity<String> unauthorized() { return ResponseEntity.status(401).body("unauthorized"); }
    @ExceptionHandler(Missing.class) ResponseEntity<String> missing() { return ResponseEntity.status(404).body("not found"); }
    @ExceptionHandler(Workflow.IdempotencyConflict.class) ResponseEntity<String> conflict() { return ResponseEntity.status(409).body("idempotency key reused for another order"); }
    @ExceptionHandler(Workflow.OutboxFull.class) ResponseEntity<String> full() { return ResponseEntity.status(503).body("outbox full; retry later"); }
    static class Unauthorized extends RuntimeException {}
    static class Missing extends RuntimeException {}
}
