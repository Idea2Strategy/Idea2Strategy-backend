package com.idea2strategy.backend.api.strategy;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandService;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryService;
import com.idea2strategy.backend.application.strategy.StrategyDraftConflictException;
import com.idea2strategy.backend.application.strategy.StrategyEditLeaseGrant;
import com.idea2strategy.backend.application.strategy.StrategyEditLeaseInvalidException;
import com.idea2strategy.backend.application.strategy.StrategyEditLeaseService;
import com.idea2strategy.backend.application.strategy.StrategyEditLeaseUnavailableException;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StrategyDocumentControllerTest {
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plusSeconds(120);
    private static final String LEASE_TOKEN = "lease-token";
    private static final String SEMANTIC = "{\"groups\":[],\"mode\":\"BASIC\"}";
    private static final String PRESENTATION = "{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";

    private StrategyDocumentQueryService queryService;
    private BasicStrategyDraftCommandService commandService;
    private StrategyEditLeaseService leaseService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queryService = mock(StrategyDocumentQueryService.class);
        commandService = mock(BasicStrategyDraftCommandService.class);
        leaseService = mock(StrategyEditLeaseService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new StrategyDocumentController(queryService, commandService, leaseService))
                .setControllerAdvice(new StrategyAuthoringExceptionHandler())
                .build();
    }

    @Test
    void returnsOwnedDocumentsAsJsonObjects() throws Exception {
        when(queryService.getOwned(STRATEGY_ID)).thenReturn(document(3));

        mvc.perform(get("/api/v1/strategies/{strategyId}/document", STRATEGY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyId").value(STRATEGY_ID.toString()))
                .andExpect(jsonPath("$.semanticDocument.mode").value("BASIC"))
                .andExpect(jsonPath("$.presentationDocument.viewport.zoom").value(1))
                .andExpect(jsonPath("$.editSequence").value(3));
    }

    @Test
    void acquiresRenewsAndReleasesTheCurrentSessionsLease() throws Exception {
        when(leaseService.acquire(STRATEGY_ID)).thenReturn(new StrategyEditLeaseGrant(LEASE_TOKEN, EXPIRES_AT));
        when(leaseService.heartbeat(STRATEGY_ID, LEASE_TOKEN)).thenReturn(EXPIRES_AT);

        mvc.perform(post("/api/v1/strategies/{strategyId}/edit-lease", STRATEGY_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.leaseToken").value(LEASE_TOKEN))
                .andExpect(jsonPath("$.expiresAt").value(EXPIRES_AT.toString()));

        mvc.perform(put("/api/v1/strategies/{strategyId}/edit-lease", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseToken\":\"lease-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value(EXPIRES_AT.toString()));

        mvc.perform(delete("/api/v1/strategies/{strategyId}/edit-lease", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseToken\":\"lease-token\"}"))
                .andExpect(status().isNoContent());

        verify(leaseService).release(STRATEGY_ID, LEASE_TOKEN);
    }

    @Test
    void explicitlySavesWithSequenceAndLeaseProtection() throws Exception {
        when(commandService.saveExplicitly(
                        eq(STRATEGY_ID), eq(3L), eq(LEASE_TOKEN), eq(SEMANTIC), eq(PRESENTATION),
                        eq("basic-semantic/v1"), eq("basic-presentation/v1")))
                .thenReturn(document(4));

        mvc.perform(put("/api/v1/strategies/{strategyId}/document", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedEditSequence":3,
                                  "leaseToken":"lease-token",
                                  "semanticDocument":{"groups":[],"mode":"BASIC"},
                                  "presentationDocument":{"positions":{},"viewport":{"x":0,"y":0,"zoom":1}}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editSequence").value(4))
                .andExpect(jsonPath("$.semanticDocument.mode").value("BASIC"));
    }

    @Test
    void mapsOwnershipLeaseAndEditConflictsWithoutLeakingDocuments() throws Exception {
        when(queryService.getOwned(STRATEGY_ID)).thenThrow(new NoSuchElementException("Strategy document not found"));
        when(leaseService.acquire(STRATEGY_ID)).thenThrow(new StrategyEditLeaseUnavailableException());
        doThrow(new StrategyEditLeaseInvalidException()).when(leaseService).release(STRATEGY_ID, LEASE_TOKEN);
        when(commandService.saveExplicitly(
                        eq(STRATEGY_ID), eq(3L), eq(LEASE_TOKEN), eq(SEMANTIC), eq(PRESENTATION),
                        eq("basic-semantic/v1"), eq("basic-presentation/v1")))
                .thenThrow(new StrategyDraftConflictException());

        mvc.perform(get("/api/v1/strategies/{strategyId}/document", STRATEGY_ID))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/strategies/{strategyId}/edit-lease", STRATEGY_ID))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/strategies/{strategyId}/edit-lease", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseToken\":\"lease-token\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/strategies/{strategyId}/document", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedEditSequence":3,
                                  "leaseToken":"lease-token",
                                  "semanticDocument":{"groups":[],"mode":"BASIC"},
                                  "presentationDocument":{"positions":{},"viewport":{"x":0,"y":0,"zoom":1}}
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsMissingDocumentContentAndLeaseTokens() throws Exception {
        mvc.perform(put("/api/v1/strategies/{strategyId}/document", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedEditSequence\":0,\"leaseToken\":\"lease-token\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/v1/strategies/{strategyId}/edit-lease", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private static StrategyDocument document(long editSequence) {
        return new StrategyDocument(
                STRATEGY_ID,
                SEMANTIC,
                PRESENTATION,
                "basic-semantic/v1",
                "basic-presentation/v1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                editSequence,
                NOW,
                NOW);
    }
}
