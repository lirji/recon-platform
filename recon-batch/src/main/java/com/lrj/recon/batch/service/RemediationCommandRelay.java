package com.lrj.recon.batch.service;

import com.lrj.recon.core.application.port.out.RemediationCommandOutboxRepository;
import com.lrj.recon.core.application.port.out.RemediationSuggestionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "recon.remediation.relay-enabled", havingValue = "true")
public class RemediationCommandRelay {
    private final RemediationCommandOutboxRepository commands;
    private final RemediationSuggestionRepository suggestions;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int maxAttempts;
    private final TransactionTemplate transactions;
    private final String workerId = "recon-remediation-" + UUID.randomUUID();

    public RemediationCommandRelay(RemediationCommandOutboxRepository commands,
                                   RemediationSuggestionRepository suggestions,
                                   KafkaTemplate<String, String> kafka,
                                   TransactionTemplate transactions,
                                   @Value("${recon.remediation.command-topic:benefit.remediation.command.v1}") String topic,
                                   @Value("${recon.remediation.max-attempts:10}") int maxAttempts) {
        this.commands = commands; this.suggestions = suggestions; this.kafka = kafka;
        this.transactions = transactions;
        this.topic = topic; this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${recon.remediation.relay-delay-ms:5000}")
    public void relay() {
        Instant now = Instant.now();
        for (var command : commands.claimDue(workerId, now, 100)) {
            try {
                kafka.send(topic, command.suggestionId(), command.payload()).join();
                transactions.executeWithoutResult(ignored -> {
                    var value = suggestions.find(command.tenantId(), command.suggestionId()).orElseThrow();
                    long version = value.version(); value.dispatch();
                    if (!suggestions.updateExpectedVersion(value, version, null))
                        throw new IllegalStateException("remediation dispatch CAS failed");
                    commands.markPublished(command.tenantId(), command.commandId(), workerId, Instant.now());
                });
            } catch (RuntimeException failure) {
                long backoff = Math.min(300, 1L << Math.min(command.attemptCount(), 8));
                commands.markFailed(command.tenantId(), command.commandId(), workerId,
                        Instant.now().plusSeconds(backoff), maxAttempts, failure.getMessage());
            }
        }
    }
}
