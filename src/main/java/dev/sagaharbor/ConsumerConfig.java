package dev.sagaharbor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class ConsumerConfig {
    // A Redis outage or full outbox must never become a silently skipped result.
    @Bean DefaultErrorHandler resultErrorHandler() {
        return new DefaultErrorHandler((record, exception) -> {
            throw new IllegalStateException("Result was not processed: " + record.topic() + "/" + record.offset(), exception);
        }, new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
