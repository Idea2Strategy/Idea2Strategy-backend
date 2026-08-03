package com.idea2strategy.backend.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandService;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionAuthorizationPort;
import com.idea2strategy.backend.application.batch.BatchCategoryPort;
import com.idea2strategy.backend.persistence.batch.DurableBatchStore;
import com.idea2strategy.backend.persistence.caseoperations.CaseResponseDeadlineJooqAdapter;
import com.idea2strategy.backend.persistence.identity.IdentityExpiryJdbcAdapter;
import com.idea2strategy.backend.persistence.notification.EmailDeliveryGateway;
import com.idea2strategy.backend.persistence.notification.NotificationEmailWorker;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore;
import com.idea2strategy.backend.persistence.sanction.AccountSanctionJdbcAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        name = "idea2strategy.batch.deadline.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class DeadlineBatchConfiguration {
    private static final UUID APPLY_PERMISSION = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID LIFT_PERMISSION = UUID.fromString("50000000-0000-4000-8000-000000000005");

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    SanctionExpiryBatchCategoryPort sanctionExpiryBatchCategoryPort(
            JdbcTemplate jdbc, ObjectMapper json) {
        AccountSanctionJdbcAdapter adapter = new AccountSanctionJdbcAdapter(jdbc, json);
        AccountSanctionAuthorizationPort rejectManualAuthorization = (context, permission, evaluatedAt) ->
                new AccountSanctionAuthorizationPort.Decision(
                        false, "BATCH_MANUAL_SANCTION_FORBIDDEN", null,
                        Set.of(), Set.of(), false, false);
        AccountSanctionCommandService commands = new AccountSanctionCommandService(
                adapter, rejectManualAuthorization, adapter, adapter,
                APPLY_PERMISSION, LIFT_PERMISSION, new DatabaseClock(jdbc));
        return new SanctionExpiryBatchCategoryPort(adapter, commands, jdbc);
    }

    @Bean
    @ConditionalOnBean(EmailDeliveryGateway.class)
    NotificationDeliveryBatchCategoryPort notificationDeliveryBatchCategoryPort(
            JdbcTemplate jdbc,
            ObjectMapper json,
            EmailDeliveryGateway gateway,
            @Value("${batch.notification.maximum-attempts:5}") int maximumAttempts,
            @Value("${batch.notification.retry-delay:PT1M}") Duration retryDelay) {
        TransactionalOutboxStore outbox = new TransactionalOutboxStore(jdbc);
        NotificationEmailWorker worker = new NotificationEmailWorker(jdbc, json, outbox, gateway);
        return new NotificationDeliveryBatchCategoryPort(outbox, worker, maximumAttempts, retryDelay, jdbc);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    CaseDeadlineBatchCategoryPort caseDeadlineBatchCategoryPort(
            JdbcTemplate jdbc, ObjectMapper json) {
        return new CaseDeadlineBatchCategoryPort(
                new CaseResponseDeadlineJooqAdapter(jdbc, json), jdbc);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    IdentityExpiryJdbcAdapter identityExpiryJdbcAdapter(
            JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new IdentityExpiryJdbcAdapter(jdbc, transactions);
    }

    @Bean
    @ConditionalOnBean(IdentityExpiryJdbcAdapter.class)
    SessionExpiryBatchCategoryPort sessionExpiryBatchCategoryPort(
            IdentityExpiryJdbcAdapter expiry, JdbcTemplate jdbc) {
        return new SessionExpiryBatchCategoryPort(expiry, jdbc);
    }

    @Bean
    @ConditionalOnBean(IdentityExpiryJdbcAdapter.class)
    DelegatedCredentialExpiryBatchCategoryPort delegatedCredentialExpiryBatchCategoryPort(
            IdentityExpiryJdbcAdapter expiry, JdbcTemplate jdbc) {
        return new DelegatedCredentialExpiryBatchCategoryPort(expiry, jdbc);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    BatchEvidenceJdbcAdapter batchEvidenceJdbcAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        return new BatchEvidenceJdbcAdapter(jdbc, json);
    }

    @Bean(initMethod = "initialize")
    @ConditionalOnBean(BatchEvidenceJdbcAdapter.class)
    DurableDeadlineBatchRuntime durableDeadlineBatchRuntime(
            DurableBatchStore store,
            List<BatchCategoryPort> ports,
            BatchEvidenceJdbcAdapter evidence,
            @Value("${idea2strategy.batch.deadline.job-code}") String jobCode,
            @Value("${idea2strategy.batch.deadline.job-version}") String jobVersion,
            @Value("${idea2strategy.batch.deadline.job-content-hash}") String jobContentHash,
            @Value("${idea2strategy.batch.deadline.runtime-policy-version}")
                    String runtimePolicyVersion,
            @Value("${idea2strategy.batch.deadline.worker-id}") String workerId,
            @Value("${idea2strategy.batch.deadline.lease-duration}") Duration leaseDuration,
            @Value("${idea2strategy.batch.deadline.retry-delay}") Duration retryDelay,
            @Value("${idea2strategy.batch.deadline.discovery-overlap}") Duration discoveryOverlap,
            @Value("${idea2strategy.batch.deadline.trigger-window}") Duration triggerWindow,
            @Value("${idea2strategy.batch.deadline.batch-size}") int batchSize,
            @Value("${idea2strategy.batch.deadline.maximum-attempts}") int maximumAttempts) {
        return new DurableDeadlineBatchRuntime(
                store, ports, evidence::recordDurableRun,
                new DurableDeadlineBatchRuntime.Settings(
                        jobCode, jobVersion, jobContentHash, runtimePolicyVersion, workerId,
                        leaseDuration, retryDelay, discoveryOverlap, triggerWindow,
                        batchSize, maximumAttempts));
    }

    @Bean
    @ConditionalOnBean(DurableDeadlineBatchRuntime.class)
    DeadlineBatchRunner deadlineBatchRunner(DurableDeadlineBatchRuntime runtime) {
        return new DeadlineBatchRunner(runtime);
    }

    private static final class DatabaseClock extends Clock {
        private final JdbcTemplate jdbc;

        private DatabaseClock(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }

        @Override public Clock withZone(ZoneId zone) {
            if (!getZone().equals(zone)) throw new IllegalArgumentException("batch clock is fixed to UTC");
            return this;
        }

        @Override public Instant instant() {
            return Objects.requireNonNull(
                    jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class)).toInstant();
        }
    }
}
