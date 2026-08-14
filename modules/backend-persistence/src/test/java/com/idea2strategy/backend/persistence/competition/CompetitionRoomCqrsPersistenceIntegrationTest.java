package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.competition.RoomInvitationIssueRequest;
import com.idea2strategy.backend.application.competition.RoomConfigurationUpdate;
import com.idea2strategy.backend.application.competition.RoomConfigurationUpdateOutcome;
import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import com.idea2strategy.backend.domain.competition.LiveRoomRules;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomInvitationCredentialType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CompetitionRoomCqrsPersistenceIntegrationTest.TestApplication.class)
class CompetitionRoomCqrsPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OPERATOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID ROOM_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_ROOM_ID = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID INVITATION_ID = UUID.fromString("50000000-0000-4000-8000-000000000003");
    private static final UUID SCORING_VERSION_ID = UUID.fromString("51000000-0000-4000-8000-000000000001");
    private static final UUID FEE_POLICY_ID = UUID.fromString("52000000-0000-4000-8000-000000000001");
    private static final UUID BUFFER_POLICY_ID = UUID.fromString("53000000-0000-4000-8000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private CompetitionRoomJpaCommandAdapter commandAdapter;

    @Autowired
    private CompetitionRoomJooqQueryAdapter queryAdapter;

    @Autowired
    private PublicRoomSearchJooqAdapter publicRoomSearchAdapter;

    @Autowired
    private RoomInvitationJooqAdapter invitationAdapter;

    @Autowired
    private RoomConfigurationJooqAdapter configurationAdapter;

    @Autowired
    private OwnedRoomManagementJooqAdapter ownedRoomManagementAdapter;

    @Autowired
    private RoomScheduleTransitionJooqAdapter transitionAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareReferences() {
        jdbcTemplate.update("delete from competition.room_invitations");
        jdbcTemplate.update("delete from competition.room_events");
        jdbcTemplate.update("delete from competition.room_schedules");
        jdbcTemplate.update("delete from competition.live_room_rules");
        jdbcTemplate.update("delete from competition.room_rules");
        jdbcTemplate.update("delete from competition.rooms");
        jdbcTemplate.update("delete from competition.scoring_template_versions where id = ?", SCORING_VERSION_ID);
        jdbcTemplate.update("delete from trading.fee_policy_versions where id = ?", FEE_POLICY_ID);
        jdbcTemplate.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER_POLICY_ID);
        jdbcTemplate.update("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbcTemplate.update("delete from identity.accounts where id = ?", OWNER_ID);
        jdbcTemplate.update("delete from operations.operator_accounts where id = ?", OPERATOR_ID);
        jdbcTemplate.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?)",
                OWNER_ID,
                CREATED_AT.atOffset(ZoneOffset.UTC));
        jdbcTemplate.update(
                "insert into operations.operator_accounts "
                        + "(id, status, created_at) values (?, 'ACTIVE', ?)",
                OPERATOR_ID,
                CREATED_AT.atOffset(ZoneOffset.UTC));
        jdbcTemplate.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) "
                        + "values (?, 'TOTAL_RETURN', 'v1', '{}'::jsonb, 'scoring-v1', ?)",
                SCORING_VERSION_ID,
                CREATED_AT.atOffset(ZoneOffset.UTC));
        jdbcTemplate.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, effective_from, published_at) "
                        + "values (?, 'OFFICIAL', 'v1', 20, 'v1', 'fee-v1', ?, ?)",
                FEE_POLICY_ID,
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC));
        jdbcTemplate.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, effective_from, published_at) "
                        + "values (?, 'DEFAULT', 'v1', 0, 'v1', 'buffer-v1', ?, ?)",
                BUFFER_POLICY_ID,
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC));
    }

    @Test
    void savedRoomPreservesAccessScheduleCapitalAndScoringVersion() {
        var schedule = new RoomSchedule(
                Instant.parse("2026-08-02T00:00:00Z"),
                Instant.parse("2026-08-02T01:00:00Z"),
                Instant.parse("2026-08-03T00:00:00Z"),
                Instant.parse("2026-08-02T23:00:00Z"),
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T01:00:00Z"),
                "America/New_York");
        var room = CompetitionRoom.userLive(
                ROOM_ID,
                OWNER_ID,
                "August room",
                RoomAccessType.PUBLIC,
                SCORING_VERSION_ID,
                new BigDecimal("100000.00000000"),
                10,
                1,
                "{\"minimumTrades\":5}",
                FEE_POLICY_ID,
                BUFFER_POLICY_ID,
                new LiveRoomRules("COUNT_UNTIL_END", 3600, 5),
                schedule,
                CREATED_AT);

        commandAdapter.save(room);
        var loaded = queryAdapter.findById(ROOM_ID).orElseThrow();

        assertThat(loaded.accessType()).isEqualTo(RoomAccessType.PUBLIC);
        assertThat(loaded.schedule()).isEqualTo(schedule);
        assertThat(loaded.initialCashAmount()).isEqualByComparingTo("100000.00000000");
        assertThat(loaded.scoringTemplateVersionId()).isEqualTo(SCORING_VERSION_ID);
        assertThat(loaded.liveRules()).isEqualTo(new LiveRoomRules("COUNT_UNTIL_END", 3600, 5));
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from competition.live_room_rules where room_id = ?",
                        Integer.class,
                        ROOM_ID))
                .isEqualTo(1);
    }

    @Test
    void failedRulesInsertRollsBackEveryRoomRow() {
        var room = CompetitionRoom.userLive(
                ROOM_ID,
                OWNER_ID,
                "Rollback room",
                RoomAccessType.SECRET,
                SCORING_VERSION_ID,
                new BigDecimal("100000.00000000"),
                10,
                1,
                "{\"minimumTrades\":5}",
                UUID.fromString("52000000-0000-4000-8000-000000000099"),
                BUFFER_POLICY_ID,
                new LiveRoomRules("COUNT_UNTIL_END", 3600, 5),
                new RoomSchedule(
                        CREATED_AT.plusSeconds(60),
                        CREATED_AT.plusSeconds(120),
                        CREATED_AT.plusSeconds(240),
                        CREATED_AT.plusSeconds(180),
                        CREATED_AT.plusSeconds(3840),
                        CREATED_AT.plusSeconds(3900),
                        "UTC"),
                CREATED_AT);

        assertThatThrownBy(() -> commandAdapter.save(room)).isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from competition.rooms where id = ?", Integer.class, ROOM_ID))
                .isZero();
    }

    @Test
    void savedOfficialRoomPreservesOperatorAndLockedRuleSnapshot() {
        var schedule = new RoomSchedule(
                CREATED_AT.plusSeconds(60),
                CREATED_AT.plusSeconds(120),
                CREATED_AT.plusSeconds(240),
                CREATED_AT.plusSeconds(180),
                CREATED_AT.plusSeconds(3840),
                CREATED_AT.plusSeconds(3900),
                "UTC");
        var room = CompetitionRoom.platformLive(
                ROOM_ID,
                OPERATOR_ID,
                "Official August room",
                RoomAccessType.PUBLIC,
                SCORING_VERSION_ID,
                new BigDecimal("100000.00000000"),
                100,
                1,
                "{\"minimumAccountAgeDays\":30}",
                "{\"market\":\"US\"}",
                "{\"minimumTrades\":5}",
                FEE_POLICY_ID,
                BUFFER_POLICY_ID,
                "precision-2026-08",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                new LiveRoomRules("COUNT_UNTIL_END", 3600, 5),
                schedule,
                CREATED_AT);

        commandAdapter.save(room);

        assertThat(jdbcTemplate.queryForObject(
                        "select created_by_operator_id from competition.rooms where id = ?",
                        UUID.class,
                        ROOM_ID))
                .isEqualTo(OPERATOR_ID);
        assertThat(jdbcTemplate.queryForObject(
                        "select organizer_type::text from competition.rooms where id = ?",
                        String.class,
                        ROOM_ID))
                .isEqualTo("PLATFORM");
        assertThat(jdbcTemplate.queryForObject(
                        "select precision_rules_version from competition.room_rules where room_id = ?",
                        String.class,
                        ROOM_ID))
                .isEqualTo("precision-2026-08");
        assertThat(jdbcTemplate.queryForObject(
                        "select locked_at from competition.room_rules where room_id = ?",
                        java.time.OffsetDateTime.class,
                        ROOM_ID)
                .toInstant())
                .isEqualTo(CREATED_AT);
    }

    @Test
    void publicDiscoveryReturnsOnlyRecruitingPublicRooms() {
        commandAdapter.save(userRoom(ROOM_ID, "Visible August room", RoomAccessType.PUBLIC));
        commandAdapter.save(userRoom(SECOND_ROOM_ID, "Hidden secret room", RoomAccessType.SECRET));
        jdbcTemplate.update(
                "update competition.rooms set status = 'RECRUITING' where id in (?, ?)",
                ROOM_ID,
                SECOND_ROOM_ID);

        var found = publicRoomSearchAdapter.search("august", null, null, 20);

        assertThat(found).extracting(item -> item.id()).containsExactly(ROOM_ID);
    }

    @Test
    void invitationStoresOnlyDigestCapsExpiryAndCanBeConsumedOnce() {
        commandAdapter.save(userRoom(ROOM_ID, "Secret room", RoomAccessType.SECRET));
        jdbcTemplate.update("update competition.rooms set status = 'RECRUITING' where id = ?", ROOM_ID);
        Instant issuedAt = CREATED_AT.plusSeconds(90);
        Instant participationClosesAt = CREATED_AT.plusSeconds(180);
        String digest = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        var issued = invitationAdapter.issue(new RoomInvitationIssueRequest(
                        INVITATION_ID,
                        ROOM_ID,
                        OWNER_ID,
                        RoomInvitationCredentialType.CODE,
                        digest,
                        issuedAt,
                        CREATED_AT.plusSeconds(600)))
                .orElseThrow();

        assertThat(issued.expiresAt()).isEqualTo(participationClosesAt);
        assertThat(jdbcTemplate.queryForObject(
                        "select credential_digest from competition.room_invitations where id = ?",
                        String.class,
                        INVITATION_ID))
                .isEqualTo(digest);
        assertThat(invitationAdapter.consume(digest, issuedAt.plusSeconds(1)))
                .hasValueSatisfying(consumed -> assertThat(consumed.roomId()).isEqualTo(ROOM_ID));
        assertThat(invitationAdapter.consume(digest, issuedAt.plusSeconds(2))).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "select revocation_reason_code from competition.room_invitations where id = ?",
                        String.class,
                        INVITATION_ID))
                .isEqualTo("CONSUMED");
    }

    @Test
    void ownerManagementQueryRestoresConfigurationAndInvitationMetadata() {
        commandAdapter.save(userRoom(ROOM_ID, "Managed secret room", RoomAccessType.SECRET));
        jdbcTemplate.update("update competition.rooms set status = 'RECRUITING' where id = ?", ROOM_ID);
        invitationAdapter.issue(new RoomInvitationIssueRequest(
                        INVITATION_ID,
                        ROOM_ID,
                        OWNER_ID,
                        RoomInvitationCredentialType.LINK,
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                        CREATED_AT.plusSeconds(90),
                        CREATED_AT.plusSeconds(150)))
                .orElseThrow();

        assertThat(ownedRoomManagementAdapter.findOwnedBy(OWNER_ID, 50))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.roomId()).isEqualTo(ROOM_ID);
                    assertThat(view.name()).isEqualTo("Managed secret room");
                    assertThat(view.accessType()).isEqualTo("SECRET");
                    assertThat(view.scoringTemplateVersionId()).isEqualTo(SCORING_VERSION_ID);
                    assertThat(view.minimumOperationSeconds()).isEqualTo(3600);
                    assertThat(view.invitations()).singleElement().satisfies(invitation -> {
                        assertThat(invitation.invitationId()).isEqualTo(INVITATION_ID);
                        assertThat(invitation.credentialType()).isEqualTo("LINK");
                    });
                    assertThat(view.participations()).isEmpty();
                });
        assertThat(ownedRoomManagementAdapter.findOwnedBy(UUID.randomUUID(), 50)).isEmpty();
    }

    @Test
    void onlyTheSecretRoomOwnerCanIssueOrRevokeAnInvitation() {
        commandAdapter.save(userRoom(ROOM_ID, "Owned secret room", RoomAccessType.SECRET));
        UUID outsiderId = UUID.fromString("10000000-0000-4000-8000-000000000099");
        var request = new RoomInvitationIssueRequest(
                INVITATION_ID,
                ROOM_ID,
                outsiderId,
                RoomInvitationCredentialType.LINK,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                CREATED_AT.plusSeconds(90),
                CREATED_AT.plusSeconds(120));

        assertThat(invitationAdapter.issue(request)).isEmpty();

        var ownerRequest = new RoomInvitationIssueRequest(
                request.id(),
                request.roomId(),
                OWNER_ID,
                request.credentialType(),
                request.credentialDigest(),
                request.issuedAt(),
                request.requestedExpiresAt());
        assertThat(invitationAdapter.issue(ownerRequest)).isPresent();
        assertThat(invitationAdapter.revoke(ROOM_ID, INVITATION_ID, outsiderId, CREATED_AT.plusSeconds(100)))
                .isFalse();
        assertThat(invitationAdapter.revoke(ROOM_ID, INVITATION_ID, OWNER_ID, CREATED_AT.plusSeconds(101)))
                .isTrue();
        assertThat(invitationAdapter.consume(request.credentialDigest(), CREATED_AT.plusSeconds(102)))
                .isEmpty();
    }

    @Test
    void draftOwnerCanAtomicallyReplaceRulesAndScheduleBeforeRecruitment() {
        commandAdapter.save(userRoom(ROOM_ID, "Draft room", RoomAccessType.PUBLIC));
        var update = configurationUpdate(
                RoomAccessType.PUBLIC, CREATED_AT.plusSeconds(30), CREATED_AT.plusSeconds(600));

        assertThat(configurationAdapter.update(update)).isEqualTo(RoomConfigurationUpdateOutcome.UPDATED);

        assertThat(jdbcTemplate.queryForObject(
                        "select name from competition.rooms where id = ?", String.class, ROOM_ID))
                .isEqualTo("Updated room");
        assertThat(jdbcTemplate.queryForMap(
                        "select initial_cash_amount, bot_participation_limit, per_account_bot_limit, "
                                + "scoring_parameters::text as scoring_parameters, rules_hash, locked_at "
                                + "from competition.room_rules where room_id = ?",
                        ROOM_ID))
                .containsEntry("bot_participation_limit", 8)
                .containsEntry("per_account_bot_limit", 2)
                .containsEntry("rules_hash", "updated-rules-hash");
        assertThat(jdbcTemplate.queryForObject(
                        "select recruitment_opens_at from competition.room_schedules where room_id = ?",
                        java.time.OffsetDateTime.class,
                        ROOM_ID).toInstant())
                .isEqualTo(CREATED_AT.plusSeconds(600));
    }

    @Test
    void failedConfigurationSnapshotRollsBackEveryTable() {
        commandAdapter.save(userRoom(ROOM_ID, "Draft room", RoomAccessType.PUBLIC));
        var valid = configurationUpdate(
                RoomAccessType.PUBLIC, CREATED_AT.plusSeconds(30), CREATED_AT.plusSeconds(600));
        var invalid = new RoomConfigurationUpdate(
                valid.roomId(),
                valid.creatorAccountId(),
                valid.name(),
                valid.accessType(),
                valid.scoringTemplateVersionId(),
                valid.initialCashAmount(),
                valid.botParticipationLimit(),
                valid.perAccountBotLimit(),
                valid.scoringParameters(),
                UUID.fromString("52000000-0000-4000-8000-000000000099"),
                valid.buyingPowerBufferPolicyId(),
                valid.rulesHash(),
                valid.liveRules(),
                valid.schedule(),
                valid.observedAt());

        assertThatThrownBy(() -> configurationAdapter.update(invalid)).isInstanceOf(RuntimeException.class);

        assertThat(roomName()).isEqualTo("Draft room");
        assertThat(jdbcTemplate.queryForObject(
                        "select initial_cash_amount from competition.room_rules where room_id = ?",
                        BigDecimal.class,
                        ROOM_ID))
                .isEqualByComparingTo("100000.00000000");
        assertThat(jdbcTemplate.queryForObject(
                        "select recruitment_opens_at from competition.room_schedules where room_id = ?",
                        java.time.OffsetDateTime.class,
                        ROOM_ID).toInstant())
                .isEqualTo(CREATED_AT.plusSeconds(60));
    }

    @Test
    void accessTypeAndEveryConfigurationMutationAreRejectedAtRecruitmentBoundary() {
        commandAdapter.save(userRoom(ROOM_ID, "Draft room", RoomAccessType.PUBLIC));
        String originalHash = jdbcTemplate.queryForObject(
                "select rules_hash from competition.room_rules where room_id = ?", String.class, ROOM_ID);

        assertThat(configurationAdapter.update(configurationUpdate(
                        RoomAccessType.SECRET, CREATED_AT.plusSeconds(30), CREATED_AT.plusSeconds(600))))
                .isEqualTo(RoomConfigurationUpdateOutcome.ACCESS_TYPE_IMMUTABLE);
        assertThat(configurationAdapter.update(configurationUpdate(
                        RoomAccessType.PUBLIC, CREATED_AT.plusSeconds(60), CREATED_AT.plusSeconds(600))))
                .isEqualTo(RoomConfigurationUpdateOutcome.RECRUITMENT_LOCKED);
        jdbcTemplate.update("update competition.rooms set status = 'RECRUITING' where id = ?", ROOM_ID);
        assertThat(configurationAdapter.update(configurationUpdate(
                        RoomAccessType.PUBLIC, CREATED_AT.plusSeconds(59), CREATED_AT.plusSeconds(600))))
                .isEqualTo(RoomConfigurationUpdateOutcome.RECRUITMENT_LOCKED);

        assertThat(jdbcTemplate.queryForObject(
                        "select rules_hash from competition.room_rules where room_id = ?", String.class, ROOM_ID))
                .isEqualTo(originalHash);
        assertThat(jdbcTemplate.queryForObject(
                        "select name from competition.rooms where id = ?", String.class, ROOM_ID))
                .isEqualTo("Draft room");
    }

    @Test
    void proposedScheduleCannotMoveRecruitmentToTheObservedPast() {
        commandAdapter.save(userRoom(ROOM_ID, "Draft room", RoomAccessType.PUBLIC));
        String originalHash = jdbcTemplate.queryForObject(
                "select rules_hash from competition.room_rules where room_id = ?", String.class, ROOM_ID);

        assertThat(configurationAdapter.update(configurationUpdate(
                        RoomAccessType.PUBLIC, CREATED_AT.plusSeconds(30), CREATED_AT.plusSeconds(29))))
                .isEqualTo(RoomConfigurationUpdateOutcome.RECRUITMENT_LOCKED);

        assertThat(roomName()).isEqualTo("Draft room");
        assertThat(jdbcTemplate.queryForObject(
                        "select rules_hash from competition.room_rules where room_id = ?", String.class, ROOM_ID))
                .isEqualTo(originalHash);
        assertThat(jdbcTemplate.queryForObject(
                        "select recruitment_opens_at from competition.room_schedules where room_id = ?",
                        java.time.OffsetDateTime.class,
                        ROOM_ID).toInstant())
                .isEqualTo(CREATED_AT.plusSeconds(60));
    }

    @Test
    void creationAdapterCannotBypassTheConfigurationLockBySavingAnExistingId() {
        commandAdapter.save(userRoom(ROOM_ID, "Draft room", RoomAccessType.PUBLIC));
        jdbcTemplate.update("update competition.rooms set status = 'RECRUITING' where id = ?", ROOM_ID);

        assertThatThrownBy(() -> commandAdapter.save(userRoom(
                        ROOM_ID, "Bypass attempt", RoomAccessType.PUBLIC)))
                .isInstanceOf(RuntimeException.class);

        assertThat(roomStatus()).isEqualTo("RECRUITING");
        assertThat(roomName()).isEqualTo("Draft room");
    }

    @RepeatedTest(10)
    void scheduleTransitionAndConfigurationUpdateSerializeToOneCoherentSnapshot() throws Exception {
        commandAdapter.save(userRoom(ROOM_ID, "Draft room", RoomAccessType.PUBLIC));
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var updateFuture = executor.submit(() -> {
                gate.await();
                return configurationAdapter.update(configurationUpdate(
                        RoomAccessType.PUBLIC, CREATED_AT.plusSeconds(59), CREATED_AT.plusSeconds(600)));
            });
            var transitionFuture = executor.submit(() -> {
                gate.await();
                return transitionAdapter.advanceDue(CREATED_AT.plusSeconds(60), 10);
            });
            gate.countDown();
            var outcome = updateFuture.get();
            var transition = transitionFuture.get();

            // Both counts, not only the transitions. When the configuration update wins the lock the
            // transition correctly applies nothing, and reporting the opening SELECT's candidate count
            // made the report say one room advanced with zero transitions — which the report's own
            // invariant rejects, so this test failed on whichever repetition lost the race (#260).
            if (outcome == RoomConfigurationUpdateOutcome.UPDATED) {
                assertThat(transition.transitionsApplied()).isZero();
                assertThat(transition.roomsAdvanced()).isZero();
                assertThat(roomStatus()).isEqualTo("DRAFT");
                assertThat(roomName()).isEqualTo("Updated room");
            } else {
                assertThat(outcome).isEqualTo(RoomConfigurationUpdateOutcome.RECRUITMENT_LOCKED);
                assertThat(transition.transitionsApplied()).isEqualTo(1);
                assertThat(transition.roomsAdvanced()).isEqualTo(1);
                assertThat(roomStatus()).isEqualTo("RECRUITING");
                assertThat(roomName()).isEqualTo("Draft room");
            }
        }
    }

    private RoomConfigurationUpdate configurationUpdate(
            RoomAccessType accessType, Instant observedAt, Instant recruitmentAt) {
        return new RoomConfigurationUpdate(
                ROOM_ID,
                OWNER_ID,
                "Updated room",
                accessType,
                SCORING_VERSION_ID,
                new BigDecimal("200000.00000000"),
                8,
                2,
                "{\"minimumTrades\":10}",
                FEE_POLICY_ID,
                BUFFER_POLICY_ID,
                "updated-rules-hash",
                new LiveRoomRules("RETURN_ON_STOP", 7200, 10),
                new RoomSchedule(
                        recruitmentAt,
                        recruitmentAt.plusSeconds(60),
                        recruitmentAt.plusSeconds(180),
                        recruitmentAt.plusSeconds(120),
                        recruitmentAt.plusSeconds(7380),
                        recruitmentAt.plusSeconds(7440),
                        "UTC"),
                observedAt);
    }

    private String roomStatus() {
        return jdbcTemplate.queryForObject(
                "select status::text from competition.rooms where id = ?", String.class, ROOM_ID);
    }

    private String roomName() {
        return jdbcTemplate.queryForObject(
                "select name from competition.rooms where id = ?", String.class, ROOM_ID);
    }

    private static CompetitionRoom userRoom(UUID id, String name, RoomAccessType accessType) {
        return CompetitionRoom.userLive(
                id,
                OWNER_ID,
                name,
                accessType,
                SCORING_VERSION_ID,
                new BigDecimal("100000.00000000"),
                10,
                1,
                "{}",
                FEE_POLICY_ID,
                BUFFER_POLICY_ID,
                new LiveRoomRules("COUNT_UNTIL_END", 3600, 5),
                new RoomSchedule(
                        CREATED_AT.plusSeconds(60),
                        CREATED_AT.plusSeconds(120),
                        CREATED_AT.plusSeconds(240),
                        CREATED_AT.plusSeconds(180),
                        CREATED_AT.plusSeconds(3840),
                        CREATED_AT.plusSeconds(3900),
                        "UTC"),
                CREATED_AT);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = CompetitionRoomJpaEntity.class)
    @EnableJpaRepositories(basePackageClasses = CompetitionRoomSpringDataRepository.class)
    @Import({
        CompetitionRoomJpaCommandAdapter.class,
        CompetitionRoomJooqQueryAdapter.class,
        PublicRoomSearchJooqAdapter.class,
        RoomInvitationJooqAdapter.class,
        RoomConfigurationJooqAdapter.class,
        OwnedRoomManagementJooqAdapter.class,
        RoomScheduleTransitionJooqAdapter.class,
        ObjectMapper.class
    })
    static class TestApplication {}
}
