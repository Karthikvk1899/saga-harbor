package dev.sagaharbor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class WorkflowIT {
    @Container static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    StringRedisTemplate redis;
    Workflow workflow;

    @BeforeEach void setup() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        workflow = new Workflow(redis, new ObjectMapper(), 100);
    }
    @Test void successAndDuplicateResult() {
        UUID key = UUID.randomUUID();
        Workflow.Started first = workflow.begin("order-1", key);
        assertTrue(first.created());
        assertEquals(first.id(), workflow.begin("order-1", key).id());
        assertThrows(Workflow.IdempotencyConflict.class, () -> workflow.begin("order-2", key));
        String reserve = (String) workflow.get(first.id()).get("commandId");
        assertEquals("CHARGING", workflow.accept(new Workflow.Result(1, first.id(), reserve, true)).state());
        assertFalse(workflow.accept(new Workflow.Result(1, first.id(), reserve, true)).applied());
        String charge = (String) workflow.get(first.id()).get("commandId");
        assertEquals("COMPLETED", workflow.accept(new Workflow.Result(1, first.id(), charge, true)).state());
        assertEquals("3", workflow.get(first.id()).get("version"));
        assertNotNull(workflow.get(first.id()).get("createdAtMs"));
        assertTrue(Long.parseLong((String)workflow.get(first.id()).get("updatedAtMs")) >= Long.parseLong((String)workflow.get(first.id()).get("createdAtMs")));
    }
    @Test void chargeFailureCompensatesAndReleaseFailureRequiresReview() {
        Workflow.Started started = workflow.begin("order-3", UUID.randomUUID());
        String reserve = (String)workflow.get(started.id()).get("commandId");
        workflow.accept(new Workflow.Result(1, started.id(), reserve, true));
        String charge = (String)workflow.get(started.id()).get("commandId");
        assertEquals("RELEASING", workflow.accept(new Workflow.Result(1, started.id(), charge, false)).state());
        String release = (String)workflow.get(started.id()).get("commandId");
        assertEquals("MANUAL_REVIEW", workflow.accept(new Workflow.Result(1, started.id(), release, false)).state());
    }
    @Test void concurrentDuplicateAppliesOnce() throws Exception {
        Workflow.Started started = workflow.begin("order-4", UUID.randomUUID());
        String reserve = (String)workflow.get(started.id()).get("commandId");
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 20).mapToObj(i -> pool.submit(() -> workflow.accept(new Workflow.Result(1, started.id(), reserve, true)))).toList();
            int applied = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS).applied()) applied++;
            assertEquals(1, applied);
        }
        assertEquals("2", workflow.get(started.id()).get("version"));
        assertEquals(2, redis.opsForHash().size("outbox:payload"));
    }
    @Test void fullOutboxFailsClosed() {
        Workflow small = new Workflow(redis, new ObjectMapper(), 1);
        var started = small.begin("order-5", UUID.randomUUID());
        assertThrows(Workflow.OutboxFull.class, () -> small.begin("order-6", UUID.randomUUID()));
        String reserve = (String)small.get(started.id()).get("commandId");
        assertThrows(Workflow.OutboxFull.class, () -> small.accept(new Workflow.Result(1, started.id(), reserve, true)));
        assertEquals("RESERVING", small.get(started.id()).get("state"));
    }
    @Test void reservationFailureIsTerminalAndReplayIsIgnored() {
        var started = workflow.begin("order-7", UUID.randomUUID());
        String reserve = (String)workflow.get(started.id()).get("commandId");
        assertEquals("FAILED", workflow.accept(new Workflow.Result(1, started.id(), reserve, false)).state());
        assertFalse(workflow.accept(new Workflow.Result(1, started.id(), reserve, true)).applied());
        assertEquals(1L, redis.opsForHash().size("outbox:payload"));
    }
    @Test void concurrentIdempotentCreationReturnsOneSaga() throws Exception {
        UUID key = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(10)) {
            var futures = java.util.stream.IntStream.range(0, 20).mapToObj(i -> pool.submit(() -> workflow.begin("same-order", key))).toList();
            String id = futures.get(0).get(10, TimeUnit.SECONDS).id();
            for (var future : futures) assertEquals(id, future.get(10, TimeUnit.SECONDS).id());
        }
        assertEquals(1L, redis.opsForHash().size("outbox:payload"));
    }
    @Test void leasedCommandCanBeReclaimedAfterExpiry() {
        workflow.begin("order-8", UUID.randomUUID());
        var claim = Workflow.script("claim.lua");
        var keys = java.util.List.of("outbox:payload", "outbox:ready", "outbox:leases");
        java.util.List<?> first = redis.execute(claim, keys, "1000", "30000");
        assertNotNull(first);
        java.util.List<?> unavailable = redis.execute(claim, keys, "1001", "30000");
        assertTrue(unavailable == null || unavailable.size() < 2);
        java.util.List<?> reclaimed = redis.execute(claim, keys, "31000", "30000");
        assertNotNull(reclaimed);
        assertEquals(first.get(0), reclaimed.get(0));
        assertEquals(first.get(1), reclaimed.get(1));
    }
}
