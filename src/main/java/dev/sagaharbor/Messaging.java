package dev.sagaharbor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class Messaging {
    private final Workflow workflow;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String,String> kafka;
    private final ObjectMapper json;
    private final String commands;
    private final DefaultRedisScript<List> claim = Workflow.script("claim.lua");
    private final DefaultRedisScript<Long> ackScript;

    Messaging(Workflow workflow, StringRedisTemplate redis, KafkaTemplate<String,String> kafka, ObjectMapper json,
            @Value("${app.commands-topic}") String commands) {
        this.workflow = workflow; this.redis = redis; this.kafka = kafka; this.json = json; this.commands = commands;
        ackScript = new DefaultRedisScript<>();
        ackScript.setScriptText("redis.call('ZREM', KEYS[2], ARGV[1]); return redis.call('HDEL', KEYS[1], ARGV[1])");
        ackScript.setResultType(Long.class);
    }
    @Scheduled(fixedDelay = 300)
    void relay() {
        List<?> item = redis.execute(claim, List.of("outbox:payload", "outbox:ready", "outbox:leases"),
                Long.toString(System.currentTimeMillis()), "30000");
        if (item == null || item.size() < 2) return;
        String id = (String)item.get(0), payload = (String)item.get(1);
        try {
            Workflow.Command command = json.readValue(payload, Workflow.Command.class);
            kafka.send(commands, command.sagaId(), payload).get(20, TimeUnit.SECONDS);
            redis.execute(ackScript, List.of("outbox:payload", "outbox:leases"), id);
        } catch (Exception e) {
            // Lease expires and another relay can retry. Never acknowledge before Kafka confirms.
            throw new IllegalStateException("outbox relay failed for " + id, e);
        }
    }
    @KafkaListener(topics = "${app.results-topic}")
    void result(ConsumerRecord<String,String> record, Acknowledgment acknowledgment) throws Exception {
        Workflow.Result result;
        try {
            result = json.readValue(record.value(), Workflow.Result.class);
            if (result.schemaVersion() != 1 || result.sagaId() == null || result.commandId() == null
                    || result.success() == null || !result.sagaId().equals(record.key())) throw new IllegalArgumentException("invalid envelope");
            UUID.fromString(result.sagaId()); UUID.fromString(result.commandId());
        } catch (Exception invalid) {
            // Invalid records are not silently discarded. Configure DLT topic before starting.
            kafka.send("saga.results.dlt", record.key(), record.value()).get(20, TimeUnit.SECONDS);
            acknowledgment.acknowledge(); return;
        }
        try { workflow.accept(result); }
        catch (IllegalArgumentException unknown) {
            kafka.send("saga.results.dlt", record.key(), record.value()).get(20, TimeUnit.SECONDS);
        }
        acknowledgment.acknowledge();
    }
}
