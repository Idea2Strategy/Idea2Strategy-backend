package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.application.strategy.StrategyEditLeaseCommandPort;
import com.idea2strategy.backend.domain.strategy.StrategyEditLease;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StrategyEditLeaseJpaCommandAdapter implements StrategyEditLeaseCommandPort {
    private final JdbcTemplate jdbcTemplate;

    public StrategyEditLeaseJpaCommandAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean acquire(StrategyEditLease lease, Instant now) {
        int updated = jdbcTemplate.update(
                """
                insert into strategy.strategy_edit_leases (
                    strategy_id, account_id, delegated_credential_id, lease_token_digest,
                    digest_key_version, acquired_at, heartbeat_at, expires_at
                ) values (?, ?, null, ?, ?, ?, ?, ?)
                on conflict (strategy_id) do update
                    set account_id = excluded.account_id,
                        delegated_credential_id = null,
                        lease_token_digest = excluded.lease_token_digest,
                        digest_key_version = excluded.digest_key_version,
                        acquired_at = excluded.acquired_at,
                        heartbeat_at = excluded.heartbeat_at,
                        expires_at = excluded.expires_at
                  where strategy.strategy_edit_leases.expires_at <= ?
                """,
                lease.strategyId(),
                lease.accountId(),
                lease.tokenDigest(),
                lease.digestKeyVersion(),
                lease.acquiredAt().atOffset(ZoneOffset.UTC),
                lease.heartbeatAt().atOffset(ZoneOffset.UTC),
                lease.expiresAt().atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC));
        return updated == 1;
    }

    @Override
    public boolean heartbeat(
            UUID strategyId,
            UUID accountId,
            String tokenDigest,
            Instant heartbeatAt,
            Instant expiresAt) {
        int updated = jdbcTemplate.update(
                """
                update strategy.strategy_edit_leases
                   set heartbeat_at = ?, expires_at = ?
                 where strategy_id = ?
                   and account_id = ?
                   and lease_token_digest = ?
                   and expires_at > ?
                """,
                heartbeatAt.atOffset(ZoneOffset.UTC),
                expiresAt.atOffset(ZoneOffset.UTC),
                strategyId,
                accountId,
                tokenDigest,
                heartbeatAt.atOffset(ZoneOffset.UTC));
        return updated == 1;
    }

    @Override
    public boolean release(UUID strategyId, UUID accountId, String tokenDigest) {
        return jdbcTemplate.update(
                        """
                        delete from strategy.strategy_edit_leases
                         where strategy_id = ?
                           and account_id = ?
                           and lease_token_digest = ?
                        """,
                        strategyId,
                        accountId,
                        tokenDigest)
                == 1;
    }
}
