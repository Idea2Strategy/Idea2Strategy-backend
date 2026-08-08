package com.idea2strategy.backend.persistence.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 입력 핀 존재 확인 읽기가 다시 행 잠금을 얻지 않게 한다.
 *
 * <p>{@code FOR UPDATE} 는 PostgreSQL 에서 지목한 테이블의 UPDATE 권한을 요구한다.
 * {@code idea2strategy_backend} 는 {@code backtest.run_input_pins} 와
 * {@code backtest.input_bundles} 에 {@code SELECT, INSERT} 만 가지므로, 그 절이 돌아오면 공식
 * 릴리스가 배포 환경에서 {@code permission denied} 로 실패한다(backend #241).
 *
 * <p>이 시험은 데이터베이스를 필요로 하지 않는다. 같은 것을 실제 역할로 확인하는 시험은
 * {@code db-migration} 의 {@code CentralFlywayIntegrationTest} 에 있다 — 그쪽만 있으면 Docker 가
 * 없는 기계에서 skip 되어 조용히 되돌아올 수 있으므로 둘을 함께 둔다.
 */
class BacktestRunInputPinWriterSqlTest {
    @Test
    @DisplayName("존재 확인 읽기에 행 잠금이 없다")
    void existenceReadTakesNoRowLock() {
        String sql = BacktestRunInputPinWriter.EXISTING_PIN_SQL.toLowerCase();

        assertThat(sql)
                .as("FOR UPDATE 는 UPDATE 권한을 요구하고 idea2strategy_backend 에는 그 권한이 없다. "
                        + "동시성은 pin() 이 시작에서 잡는 per-run advisory lock 이 담당하고, 두 테이블은 "
                        + "append-only 이며 run_id 가 PRIMARY KEY 다.")
                .doesNotContain("for update")
                .doesNotContain("for no key update")
                .doesNotContain("for share");
    }

    @Test
    @DisplayName("읽기가 비교에 필요한 컬럼을 모두 담는다")
    void existenceReadSelectsEveryComparedColumn() {
        // 행 잠금을 제거하면서 select 목록을 건드리지 않았음을 고정한다. 컬럼이 빠지면
        // 멱등성 비교가 조용히 통과해 서로 다른 입력을 같은 것으로 취급한다.
        String sql = BacktestRunInputPinWriter.EXISTING_PIN_SQL.toLowerCase();
        assertThat(sql).contains(
                "p.input_bundle_id",
                "p.input_bundle_fingerprint",
                "p.input_contract_version",
                "p.compiled_plan_checksum",
                "p.strategy_snapshot_hash",
                "p.execution_policy_version",
                "b.bundle_hash");
        assertThat(sql).contains("where p.run_id = ?");
    }
}
