package dev.sagaharbor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class Workflow {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final long capacity;
    private final DefaultRedisScript<List> start = script("start.lua");
    private final DefaultRedisScript<List> transition = script("transition.lua");

    public Workflow(StringRedisTemplate redis, ObjectMapper json, @Value("${app.outbox-capacity}") long capacity) {
        this.redis = redis; this.json = json; this.capacity = capacity;
    }
    static DefaultRedisScript<List> script(String name) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(name)); script.setResultType(List.class); return script;
    }
    static String sagaKey(String id) { return "saga:" + id; }
    public record Command(int schemaVersion, String eventId, String sagaId, String orderId, String commandId, String action) {}
    public record Result(int schemaVersion, String sagaId, String commandId, Boolean success) {}
    public record Started(String id, String state, boolean created) {}
    public record Outcome(boolean applied, String state) {}

    public Started begin(String orderId, UUID idempotencyKey) {
        String id = UUID.randomUUID().toString(); String commandId = UUID.randomUUID().toString(); String eventId = UUID.randomUUID().toString();
        Command command = new Command(1, eventId, id, orderId, commandId, "RESERVE");
        List<?> answer = redis.execute(start, List.of(sagaKey(id), "idem:" + idempotencyKey, "outbox:payload", "outbox:ready"),
                id, orderId, commandId, eventId, encode(command), Long.toString(capacity));
        if (answer == null || answer.isEmpty()) throw new IllegalStateException("Redis returned no decision");
        int code = Math.toIntExact((Long) answer.get(0));
        if (code == -1) throw new OutboxFull();
        String chosen = (String) answer.get(1);
        Map<Object,Object> record = redis.opsForHash().entries(sagaKey(chosen));
        if (record.isEmpty()) throw new IllegalStateException("Missing idempotent saga");
        if (!orderId.equals(record.get("orderId"))) throw new IdempotencyConflict();
        return new Started(chosen, (String) record.get("state"), code == 1);
    }
    public Map<Object,Object> get(String id) { return redis.opsForHash().entries(sagaKey(id)); }
    public Outcome accept(Result result) {
        Map<Object,Object> record = get(result.sagaId());
        if (record.isEmpty()) throw new IllegalArgumentException("unknown saga");
        String current = (String) record.get("state");
        String action = switch (current) { case "RESERVING" -> "CHARGE"; case "CHARGING" -> "RELEASE"; default -> "NONE"; };
        if (current.equals("CHARGING") && result.success()) action = "NONE";
        if (current.equals("RESERVING") && !result.success()) action = "NONE";
        String nextCommandId = UUID.randomUUID().toString(), eventId = UUID.randomUUID().toString();
        Command command = new Command(1, eventId, result.sagaId(), (String)record.get("orderId"), nextCommandId, action);
        List<?> answer = redis.execute(transition, List.of(sagaKey(result.sagaId()), "outbox:payload", "outbox:ready"),
                result.commandId(), result.success() ? "1" : "0", nextCommandId, eventId, encode(command), Long.toString(capacity));
        if (answer == null || answer.isEmpty()) throw new IllegalStateException("Redis returned no transition");
        int code = Math.toIntExact((Long)answer.get(0));
        if (code == -2) throw new OutboxFull();
        if (code == -1) throw new IllegalArgumentException("unknown saga");
        return new Outcome(code == 1, (String) answer.get(1));
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public static class OutboxFull extends RuntimeException {}
    public static class IdempotencyConflict extends RuntimeException {}
}
