package com.idea2strategy.backend.persistence.backtest;

import com.idea2strategy.backend.application.backtest.BacktestRequestIdempotencyConflictException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;

/** Writes and verifies the complete immutable producer-side input boundary for a backtest run. */
public final class BacktestRunInputPinWriter {
    private BacktestRunInputPinWriter() {}

    /**
     * 이미 고정된 입력 핀이 있는지 읽는다.
     *
     * <p>행 잠금(<code>for update of p, b</code>)이 없다. 세 가지가 그것을 대신한다.
     *
     * <ol>
     *   <li>{@code pin} 이 시작에서 실행하는 per-run advisory transaction lock 이 같은
     *       {@code run_id} 에 대한 동시 시도를 직렬화한다.
     *   <li>이 두 테이블은 append-only 다. 프로덕션 코드에 UPDATE·DELETE 가 없고, 마이그레이션의
     *       테이블 주석도 immutable 이라고 적는다. 읽은 뒤 바뀔 행이 없으므로 잠글 대상이 없다.
     *   <li>{@code run_input_pins.run_id} 가 PRIMARY KEY 이고 {@code input_bundle_id} 가 UNIQUE 다.
     *       advisory lock 을 어떤 이유로 우회한 경쟁이 있어도 제약에서 실패하고, 조용히 중복되지
     *       않는다.
     * </ol>
     *
     * <p>그리고 행 잠금은 최소권한 계약과 충돌했다. PostgreSQL 은 {@code FOR UPDATE} 가 지목한
     * 테이블에 UPDATE 권한을 요구하는데, {@code idea2strategy_backend} 는 이 두 테이블에
     * {@code SELECT, INSERT} 만 갖는다. 그래서 공식 릴리스가 배포 환경에서
     * {@code permission denied for table run_input_pins} 로 실패했다(backend #241).
     */
    static final String EXISTING_PIN_SQL =
            "select p.input_bundle_id, p.input_bundle_fingerprint, p.input_contract_version, "
                    + "p.compiled_plan_checksum, p.strategy_snapshot_hash, p.execution_policy_version, "
                    + "b.bundle_hash from backtest.run_input_pins p "
                    + "join backtest.input_bundles b on b.id = p.input_bundle_id "
                    + "where p.run_id = ?";


    public static void pin(DSLContext dsl, RunInputPin pin) {
        Objects.requireNonNull(dsl, "dsl");
        Objects.requireNonNull(pin, "pin");
        dsl.fetchOne("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", pin.runId());

        var existing = dsl.fetchOne(EXISTING_PIN_SQL, pin.runId());
        if (existing != null) {
            if (!pin.bundleId().equals(existing.get("input_bundle_id", UUID.class))
                    || !pin.inputBundleFingerprint().equals(existing.get("input_bundle_fingerprint", String.class))
                    || !pin.inputContractVersion().equals(existing.get("input_contract_version", String.class))
                    || !pin.compiledPlanChecksum().equals(existing.get("compiled_plan_checksum", String.class))
                    || !pin.strategySnapshotHash().equals(existing.get("strategy_snapshot_hash", String.class))
                    || !pin.executionPolicyVersion().equals(existing.get("execution_policy_version", String.class))
                    || !pin.inputBundleFingerprint().equals(existing.get("bundle_hash", String.class))
                    || !pin.datasets().equals(readDatasets(dsl, pin.bundleId()))
                    || !pin.features().equals(readFeatures(dsl, pin.bundleId()))) {
                throw new BacktestRequestIdempotencyConflictException();
            }
            return;
        }
        if (dsl.fetchOne("select 1 from backtest.input_bundles where run_id = ? or id = ?",
                pin.runId(), pin.bundleId()) != null) {
            throw new BacktestRequestIdempotencyConflictException();
        }

        dsl.execute(
                "insert into backtest.input_bundles (id, run_id, bundle_hash, as_of_at, locked_at) "
                        + "values (?, ?, ?, ?::timestamptz, ?::timestamptz)",
                pin.bundleId(), pin.runId(), pin.inputBundleFingerprint(), pin.pinnedAt(), pin.pinnedAt());
        for (var dataset : pin.datasets()) {
            dsl.execute(
                    "insert into backtest.input_datasets "
                            + "(input_bundle_id, dataset_manifest_id, purpose_code, locked_dataset_hash) "
                            + "values (?, ?, ?, ?)",
                    pin.bundleId(), dataset.datasetManifestId(), dataset.purposeCode(), dataset.lockedDatasetHash());
        }
        for (var feature : pin.features()) {
            dsl.execute(
                    "insert into backtest.input_feature_materializations "
                            + "(input_bundle_id, feature_materialization_id, locked_result_hash) values (?, ?, ?)",
                    pin.bundleId(), feature.featureMaterializationId(), feature.lockedResultHash());
        }
        dsl.execute(
                "insert into backtest.run_input_pins "
                        + "(run_id, input_bundle_id, input_bundle_fingerprint, input_contract_version, "
                        + "compiled_plan_checksum, strategy_snapshot_hash, execution_policy_version, pinned_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?::timestamptz)",
                pin.runId(), pin.bundleId(), pin.inputBundleFingerprint(), pin.inputContractVersion(),
                pin.compiledPlanChecksum(), pin.strategySnapshotHash(), pin.executionPolicyVersion(), pin.pinnedAt());
    }

    private static List<DatasetPin> readDatasets(DSLContext dsl, UUID bundleId) {
        return dsl.fetch(
                        "select dataset_manifest_id, purpose_code, locked_dataset_hash "
                                + "from backtest.input_datasets where input_bundle_id = ? "
                                + "order by purpose_code, dataset_manifest_id",
                        bundleId)
                .map(row -> new DatasetPin(
                        row.get("dataset_manifest_id", UUID.class),
                        row.get("purpose_code", String.class),
                        row.get("locked_dataset_hash", String.class)));
    }

    private static List<FeaturePin> readFeatures(DSLContext dsl, UUID bundleId) {
        return dsl.fetch(
                        "select feature_materialization_id, locked_result_hash "
                                + "from backtest.input_feature_materializations where input_bundle_id = ? "
                                + "order by feature_materialization_id",
                        bundleId)
                .map(row -> new FeaturePin(
                        row.get("feature_materialization_id", UUID.class),
                        row.get("locked_result_hash", String.class)));
    }

    public record RunInputPin(
            UUID runId,
            String inputBundleFingerprint,
            String inputContractVersion,
            String compiledPlanChecksum,
            String strategySnapshotHash,
            String executionPolicyVersion,
            OffsetDateTime pinnedAt,
            List<DatasetPin> datasets,
            List<FeaturePin> features) {
        public RunInputPin {
            Objects.requireNonNull(runId, "runId");
            requireSha256(inputBundleFingerprint, "inputBundleFingerprint");
            requireText(inputContractVersion, "inputContractVersion");
            requireSha256(compiledPlanChecksum, "compiledPlanChecksum");
            requireSha256(strategySnapshotHash, "strategySnapshotHash");
            requireText(executionPolicyVersion, "executionPolicyVersion");
            Objects.requireNonNull(pinnedAt, "pinnedAt");
            datasets = List.copyOf(Objects.requireNonNull(datasets, "datasets")).stream()
                    .sorted(Comparator.comparing(DatasetPin::purposeCode)
                            .thenComparing(item -> item.datasetManifestId().toString()))
                    .toList();
            features = List.copyOf(Objects.requireNonNull(features, "features")).stream()
                    .sorted(Comparator.comparing(item -> item.featureMaterializationId().toString()))
                    .toList();
            if (datasets.isEmpty()) {
                throw new IllegalArgumentException("datasets must not be empty");
            }
            if (new HashSet<>(datasets).size() != datasets.size()
                    || new HashSet<>(features).size() != features.size()) {
                throw new IllegalArgumentException("input pins must be unique");
            }
        }

        UUID bundleId() {
            return UUID.nameUUIDFromBytes(
                    ("backtest-input-bundle:" + runId).getBytes(StandardCharsets.UTF_8));
        }
    }

    public record DatasetPin(UUID datasetManifestId, String purposeCode, String lockedDatasetHash) {
        public DatasetPin {
            Objects.requireNonNull(datasetManifestId, "datasetManifestId");
            requireText(purposeCode, "purposeCode");
            requireSha256(lockedDatasetHash, "lockedDatasetHash");
        }
    }

    public record FeaturePin(UUID featureMaterializationId, String lockedResultHash) {
        public FeaturePin {
            Objects.requireNonNull(featureMaterializationId, "featureMaterializationId");
            requireSha256(lockedResultHash, "lockedResultHash");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireSha256(String value, String field) {
        requireText(value, field);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must use sha256:<64 lowercase hex>");
        }
    }
}
