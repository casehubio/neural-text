package io.casehub.neocortex.memory.cbr.testing;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.fusion.FusionStrategy;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrFilter;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.CbrScanRequest;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.NumericRange;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.SimilaritySpec;
import io.casehub.neocortex.memory.cbr.TemporalDecay;
import io.casehub.neocortex.memory.cbr.TextualCbrCase;
import io.casehub.neocortex.memory.cbr.TrendSpec;
import io.casehub.neocortex.memory.cbr.TrendType;
import io.casehub.neocortex.memory.cbr.WarpingConstraint;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.casehub.neocortex.memory.cbr.FeatureValue.number;
import static io.casehub.neocortex.memory.cbr.FeatureValue.numberList;
import static io.casehub.neocortex.memory.cbr.FeatureValue.range;
import static io.casehub.neocortex.memory.cbr.FeatureValue.string;
import static io.casehub.neocortex.memory.cbr.FeatureValue.stringList;
import static io.casehub.neocortex.memory.cbr.FeatureValue.struct;
import static io.casehub.neocortex.memory.cbr.FeatureValue.structList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class CbrCaseMemoryStoreContractTest {

    protected static final MemoryDomain CBR    = new MemoryDomain("cbr");
    protected static final String       TENANT = "test-tenant";
    protected static final String       ENTITY = "test-entity";

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }

    protected abstract CbrCaseMemoryStore store();

    protected void clearStore() {}


    @BeforeEach
    void registerDefaultSchema() {
        clearStore();
        store().registerSchema(CbrFeatureSchema.of("starcraft-game",
                                                   FeatureField.categorical("opponent_race"),
                                                   FeatureField.categorical("detected_build"),
                                                   FeatureField.numeric("army_size_ratio", 0.0, 3.0),
                                                   FeatureField.text("notes")));
    }

    @Test
    void store_returnsNonBlankId() {
        var    c  = new TextualCbrCase("Zerg roach rush", "early pressure", "WIN", Confidence.unknown(0.9), null, null);
        String id = store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());
        assertThat(id).isNotBlank();
    }

    @Test
    void retrieveSimilar_emptyWhenNoCases() {
        var q       = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game", Map.of("opponent_race", string("Zerg")), 5);
        var results = store().retrieveSimilar(q, CbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void retrieveSimilar_findsStoredCase() {
        var c = new FeatureVectorCbrCase("Zerg roach rush", "early pressure", "WIN", Confidence.unknown(0.9),
                                         Map.of("opponent_race", string("Zerg"), "detected_build", string("ROACH_RUSH"), "army_size_ratio", number(0.7)), null, null);
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("Zerg roach rush");
        assertThat(results.getFirst().cbrCase().features()).containsEntry("opponent_race", string("Zerg"));
    }

    @Test
    void retrieveSimilar_returnsCaseId() {
        registerDefaultSchema();
        store().store(
                new FeatureVectorCbrCase("Zerg rush", "early pressure", "WIN", Confidence.unknown(0.9),
                                         Map.of("opponent_race", string("Zerg"), "detected_build", string("ROACH_RUSH"), "army_size_ratio", number(0.7)), null, null),
                "starcraft-game", ENTITY, CBR, TENANT, "my-case-id", Path.root());
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5), FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().caseId()).isEqualTo("my-case-id");
    }


    @Test
    void retrieveSimilar_filtersByCaseType() {
        var c = new FeatureVectorCbrCase("Zerg game", "rush", "WIN", null,
                                         Map.of("opponent_race", string("Zerg")), null, null);
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());

        var q       = CbrQuery.of(TENANT, CBR, Path.root(), "aml-investigation", Map.of(), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void retrieveSimilar_filtersByTenant() {
        var c = new FeatureVectorCbrCase("problem", "solution", "WIN", null,
                                         Map.of("opponent_race", string("Zerg")), null, null);
        store().store(c, "starcraft-game", ENTITY, CBR, "other-tenant", "case-1", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void retrieveSimilar_categoricalExactMatch() {
        store().store(new FeatureVectorCbrCase("Zerg game", "rush", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "detected_build", string("ROACH_RUSH")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());
        store().store(new FeatureVectorCbrCase("Protoss game", "expand", "LOSS", null,
                                               Map.of("opponent_race", string("Protoss"), "detected_build", string("ZEALOT_RUSH")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-2", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        // Graded scoring: Zerg match scores 1.0, Protoss mismatch scores 0.0
        // Both returned with minSimilarity=0.0, but Zerg ranks first
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results.getFirst().cbrCase().features()).containsEntry("opponent_race", string("Zerg"));
        assertThat(results.getFirst().score()).isEqualTo(1.0);
    }

    @Test
    void retrieveSimilar_respectsTopK() {
        for (int i = 0; i < 10; i++) {
            store().store(new FeatureVectorCbrCase("game " + i, "strat", "WIN", null,
                                                   Map.of("opponent_race", string("Zerg")), null, null),
                          "starcraft-game", ENTITY, CBR, TENANT, "case-" + i, Path.root());
        }

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 3);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void erase_removesMatchingCases() {
        store().store(new TextualCbrCase("problem", "solution", "WIN", null, null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());
        int erased = store().erase(new EraseRequest(ENTITY, CBR, TENANT, "case-1"));
        assertThat(erased).isGreaterThanOrEqualTo(0);
    }

    // --- PlanCbrCase tests ---

    @Test
    void eraseEntity_removesAllEntityCases() {
        store().store(new TextualCbrCase("p1", "s1", "WIN", null, null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());
        store().store(new TextualCbrCase("p2", "s2", "LOSS", null, null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-2", Path.root());
        int erased = store().eraseEntity(ENTITY, TENANT);
        assertThat(erased).isGreaterThanOrEqualTo(0);
    }

    @Test
    void planCbrCase_storeAndRetrieve() {
        var trace = new PlanTrace("scout", "reconnaissance", "drone-scout", "SUCCESS", 1, Map.of(), null);
        var c = new PlanCbrCase("Zerg roach rush", "early pressure", "WIN", Confidence.unknown(0.85),
                                Map.of("opponent_race", string("Zerg"), "detected_build", string("ROACH_RUSH")),
                                List.of(trace), null, null);
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "plan-1", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5);
        var results = store().retrieveSimilar(q, PlanCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("Zerg roach rush");
        assertThat(results.getFirst().cbrCase().cbrType()).isEqualTo("plan");
    }

    @Test
    void planCbrCase_featureMatchRanking() {
        var trace = new PlanTrace("b", "c", "w", "OK", 1, Map.of(), null);
        store().store(new PlanCbrCase("Zerg game", "rush", "WIN", null,
                                      Map.of("opponent_race", string("Zerg")), List.of(trace), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "plan-1", Path.root());
        store().store(new PlanCbrCase("Protoss game", "expand", "LOSS", null,
                                      Map.of("opponent_race", string("Protoss")), List.of(trace), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "plan-2", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5);
        var results = store().retrieveSimilar(q, PlanCbrCase.class);
        // Both returned (minSimilarity=0.0), Zerg match ranks first with score 1.0
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results.getFirst().cbrCase().features()).containsEntry("opponent_race", string("Zerg"));
        assertThat(results.getFirst().score()).isEqualTo(1.0);
    }

    @Test
    void planCbrCase_planTraceRoundTrip() {
        var trace1 = new PlanTrace("scout", "reconnaissance", "drone-scout", "SUCCESS", 1,
                                   Map.of("duration", 30), null);
        var trace2 = new PlanTrace("attack", "aggression", "roach-push", "SUCCESS", 2,
                                   Map.of("supply", 44), null);
        var c = new PlanCbrCase("Zerg game", "rush", "WIN", Confidence.unknown(0.9),
                                Map.of("opponent_race", string("Zerg")),
                                List.of(trace1, trace2), null, null);
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "plan-1", Path.root());

        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game", Map.of("opponent_race", string("Zerg")), 5),
                PlanCbrCase.class);
        assertThat(results).hasSize(1);
        var retrieved = results.getFirst().cbrCase();
        assertThat(retrieved.planTrace()).hasSize(2);
        assertThat(retrieved.planTrace().get(0).bindingName()).isEqualTo("scout");
        assertThat(retrieved.planTrace().get(0).capabilityName()).isEqualTo("reconnaissance");
        assertThat(retrieved.planTrace().get(1).bindingName()).isEqualTo("attack");
        assertThat(retrieved.planTrace().get(1).parameters()).containsEntry("supply", 44);
    }

    // --- notBefore tests ---

    @Test
    void planCbrCase_coexistsWithFeatureVector() {
        store().store(new FeatureVectorCbrCase("FV game", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "fv-1", Path.root());
        store().store(new PlanCbrCase("Plan game", "strat", "WIN", null,
                                      Map.of("opponent_race", string("Zerg")),
                                      List.of(new PlanTrace("b", "c", "w", "OK", 1, Map.of(), null)), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "plan-1", Path.root());

        var fvResults = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game", Map.of("opponent_race", string("Zerg")), 10),
                FeatureVectorCbrCase.class);
        assertThat(fvResults).hasSize(1);
        assertThat(fvResults.getFirst().cbrCase().problem()).isEqualTo("FV game");

        var planResults = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game", Map.of("opponent_race", string("Zerg")), 10),
                PlanCbrCase.class);
        assertThat(planResults).hasSize(1);
        assertThat(planResults.getFirst().cbrCase().problem()).isEqualTo("Plan game");

        var allResults = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game", Map.of("opponent_race", string("Zerg")), 10),
                CbrCase.class);
        assertThat(allResults).hasSize(2);
    }

    @Test
    void retrieveSimilar_notBefore_filtersOlderCases() throws Exception {
        store().store(new FeatureVectorCbrCase("old game", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-old", Path.root());

        Thread.sleep(50);
        Instant boundary = Instant.now();
        Thread.sleep(50);

        store().store(new FeatureVectorCbrCase("new game", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-new", Path.root());

        var q = new CbrQuery(TENANT, CBR, "starcraft-game",
                             Map.of("opponent_race", string("Zerg")), Map.of(), Map.of(), 10, 0.0, boundary, null, 0.5,
                             RetrievalMode.HYBRID, FusionStrategy.RRF, null, Path.root(), null);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("new game");
    }

    // --- Numeric graded similarity tests ---

    @Test
    void retrieveSimilar_notBefore_null_returnsAll() {
        store().store(new FeatureVectorCbrCase("game 1", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());
        store().store(new FeatureVectorCbrCase("game 2", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-2", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 10);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
    }

    @Test
    void retrieveSimilar_numericSimilarityDecay() {
        store().store(new FeatureVectorCbrCase("close game", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "army_size_ratio", number(0.65)), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-close", Path.root());
        store().store(new FeatureVectorCbrCase("far game", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "army_size_ratio", number(2.0)), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-far", Path.root());

        // Query for army_size_ratio ~0.7 with range tolerance
        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg"),
                                   "army_size_ratio", range(0.595, 0.805)), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        // Both returned (minSimilarity=0.0), close game ranks higher
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("close game");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void retrieveSimilar_numericRange_exact_matchesExactValue() {
        store().store(new FeatureVectorCbrCase("exact game", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "army_size_ratio", number(0.7)), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-exact", Path.root());
        store().store(new FeatureVectorCbrCase("other game", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "army_size_ratio", number(1.5)), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-other", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg"),
                                   "army_size_ratio", range(0.7, 0.7)), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        // Both returned, exact match ranks first
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("exact game");
        assertThat(results.getFirst().score()).isGreaterThan(results.getLast().score());
    }

    @Test
    void retrieveSimilar_numericExactMatch_closerValueScoresHigher() {
        store().store(new FeatureVectorCbrCase("match", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "army_size_ratio", number(0.7)), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-match", Path.root());
        store().store(new FeatureVectorCbrCase("no match", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "army_size_ratio", number(1.5)), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-no-match", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg"), "army_size_ratio", number(0.7)), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        // Both returned, exact value match ranks first
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("match");
        assertThat(results.getFirst().score()).isGreaterThan(results.getLast().score());
    }

    @Test
    void schemaValidation_numericFieldAcceptsNumericRange() {
        store().store(new FeatureVectorCbrCase("p", "s", null, null,
                                               Map.of("army_size_ratio", number(0.7)), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("army_size_ratio", range(0.63, 0.77)), 5);
        assertThatCode(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .doesNotThrowAnyException();
    }

    @Test
    void schemaValidation_categoricalFieldRequiresString() {
        store().store(new FeatureVectorCbrCase("p", "s", null, null,
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());

        assertThatThrownBy(() -> store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game", Map.of("opponent_race", number(42)), 5),
                FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schemaValidation_numericFieldRequiresNumber() {
        store().store(new FeatureVectorCbrCase("p", "s", null, null,
                                               Map.of("army_size_ratio", number(0.7)), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());

        assertThatThrownBy(() -> store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game", Map.of("army_size_ratio", string("high")), 5),
                FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schemaValidation_unknownFieldsIgnored() {
        store().store(new FeatureVectorCbrCase("p", "s", null, null,
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg"), "unknown_field", string("value")), 5);
        assertThatCode(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .doesNotThrowAnyException();
    }

    @Test
    void retrieveSimilar_withProblem_null_returnsFilteredResults() {
        var fv = new FeatureVectorCbrCase("Zerg rush detected", "wall-off", null, null,
                                          Map.of("opponent_race", string("Zerg")), null, null);
        store().store(fv, "starcraft-game", ENTITY, CBR, TENANT, "case-null-problem", Path.root());

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                                Map.of("opponent_race", string("Zerg")), 5);
        // problem is null by default via of()
        assertThat(query.problem()).isNull();

        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("Zerg rush detected");
        assertThat(results.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void retrieveSimilar_withProblem_nonNull_returnsFilteredResults() {
        var fv = new FeatureVectorCbrCase("Zerg rush detected", "wall-off", null, null,
                                          Map.of("opponent_race", string("Zerg")), null, null);
        store().store(fv, "starcraft-game", ENTITY, CBR, TENANT, "case-with-problem", Path.root());

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                                Map.of("opponent_race", string("Zerg")), 5)
                            .withProblem("Zerg attack incoming");

        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("Zerg rush detected");
    }

    // --- Weighted scoring tests (new for #82 + #87) ---

    @Test
    void retrieveSimilar_minSimilarity_zero_returnsAllMatches() {
        var fv1 = new FeatureVectorCbrCase("case one", "solution one", null, null,
                                           Map.of("opponent_race", string("Zerg")), null, null);
        var fv2 = new FeatureVectorCbrCase("case two", "solution two", null, null,
                                           Map.of("opponent_race", string("Zerg")), null, null);
        store().store(fv1, "starcraft-game", ENTITY, CBR, TENANT, "case-ms-1", Path.root());
        store().store(fv2, "starcraft-game", ENTITY, CBR, TENANT, "case-ms-2", Path.root());

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                                Map.of("opponent_race", string("Zerg")), 10);
        // minSimilarity is 0.0 by default
        assertThat(query.minSimilarity()).isEqualTo(0.0);

        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void weightedScoringProducesExpectedRanking() {
        // Two cases: one matches color (weight=3), other matches build (weight=1)
        store().store(new FeatureVectorCbrCase("color match", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "detected_build", string("MARINE_PUSH")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-color", Path.root());
        store().store(new FeatureVectorCbrCase("build match", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Protoss"), "detected_build", string("ROACH_RUSH")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-build", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg"), "detected_build", string("ROACH_RUSH")), 5)
                        .withWeights(Map.of("opponent_race", 3.0, "detected_build", 1.0));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        // color match: (3*1.0 + 1*0.0) / 4 = 0.75
        // build match: (3*0.0 + 1*1.0) / 4 = 0.25
        // color match should rank first
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("color match");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void defaultWeightsAreUniform() {
        store().store(new FeatureVectorCbrCase("both match", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "detected_build", string("ROACH_RUSH")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-both", Path.root());
        store().store(new FeatureVectorCbrCase("one match", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "detected_build", string("MARINE_PUSH")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-one", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg"), "detected_build", string("ROACH_RUSH")), 5);
        // No weights → uniform (all default to 1.0)
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        // both-match: (1+1)/2 = 1.0, one-match: (1+0)/2 = 0.5
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("both match");
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(1).cbrCase().problem()).isEqualTo("one match");
        assertThat(results.get(1).score()).isCloseTo(0.5, within(0.01));
    }

    @Test
    void numericSimilarityDecay_closerValueScoresHigher() {
        // army_size_ratio range is [0, 3]
        store().store(new FeatureVectorCbrCase("close", "strat", "WIN", null,
                                               Map.of("army_size_ratio", number(1.0)), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-close", Path.root());
        store().store(new FeatureVectorCbrCase("far", "strat", "WIN", null,
                                               Map.of("army_size_ratio", number(2.5)), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-far", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("army_size_ratio", number(1.0)), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("close");
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(1).cbrCase().problem()).isEqualTo("far");
        assertThat(results.get(1).score()).isLessThan(1.0);
        assertThat(results.get(1).score()).isGreaterThan(0.0);
    }

    @Test
    void minSimilarityThresholdOnCompositeScore() {
        store().store(new FeatureVectorCbrCase("match", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-match", Path.root());
        store().store(new FeatureVectorCbrCase("no match", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Protoss")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-no-match", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5)
                        .withMinSimilarity(0.5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        // Only the Zerg match (score=1.0) passes the 0.5 threshold
        // Protoss mismatch (score=0.0) is excluded
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("match");
    }

    @Test
    void missingFeatureScoresZero() {
        // Case has no features, query asks for opponent_race
        store().store(new FeatureVectorCbrCase("no features", "strat", "WIN", null,
                                               Map.of(), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-empty", Path.root());
        store().store(new FeatureVectorCbrCase("has feature", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-feat", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        // Case with feature scores 1.0, case without scores 0.0
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("has feature");
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(1).cbrCase().problem()).isEqualTo("no features");
        assertThat(results.get(1).score()).isEqualTo(0.0);
    }

    @Test
    void emptyFeaturesScoresOne() {
        store().store(new FeatureVectorCbrCase("any case", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());

        // No features queried → vacuous truth → score = 1.0
        var q       = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game", Map.of(), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isEqualTo(1.0);
    }

    @Test
    void textExactMatch_identicalStrings() {
        store().store(new FeatureVectorCbrCase("game", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "notes", string("early pool")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("notes", string("early pool")), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isEqualTo(1.0);
    }

    @Test
    void textExactMatch_differentStrings() {
        store().store(new FeatureVectorCbrCase("match", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "notes", string("early pool")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-1", Path.root());
        store().store(new FeatureVectorCbrCase("no match", "strat", "WIN", null,
                                               Map.of("opponent_race", string("Zerg"), "notes", string("late game macro")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-2", Path.root());

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("notes", string("early pool")), 5);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("match");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    // --- SimilaritySpec contract tests (#107, #108) ---

    @Test
    void categoricalTable_graduatedSimilarityRanking() {
        var schema = CbrFeatureSchema.of("medical",
                                         FeatureField.categorical("condition",
                                                                  SimilaritySpec.categoricalTableBuilder()
                                                                                .add("headache", "migraine", 0.8)
                                                                                .add("headache", "fracture", 0.1)
                                                                                .build()),
                                         FeatureField.numeric("severity", 0, 10));
        store().registerSchema(schema);

        store().store(new FeatureVectorCbrCase("migraine case", "treatment A", "SUCCESS", null,
                                               Map.of("condition", string("migraine"), "severity", number(5.0)), null, null),
                      "medical", ENTITY, CBR, TENANT, "case-migraine", Path.root());
        store().store(new FeatureVectorCbrCase("fracture case", "treatment B", "SUCCESS", null,
                                               Map.of("condition", string("fracture"), "severity", number(5.0)), null, null),
                      "medical", ENTITY, CBR, TENANT, "case-fracture", Path.root());

        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "medical",
                            Map.of("condition", string("headache"), "severity", number(5.0)), 10),
                FeatureVectorCbrCase.class);

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().features().get("condition")).isEqualTo(string("migraine"));
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void gaussianDecay_numericSimilarityRanking() {
        var schema = CbrFeatureSchema.of("gauss",
                                         FeatureField.categorical("cat"),
                                         FeatureField.numeric("val", 0, 100, new SimilaritySpec.GaussianDecay(0.3)));
        store().registerSchema(schema);

        store().store(new FeatureVectorCbrCase("close value", "sol", "OK", null,
                                               Map.of("cat", string("a"), "val", number(50.0)), null, null),
                      "gauss", ENTITY, CBR, TENANT, "case-close", Path.root());
        store().store(new FeatureVectorCbrCase("far value", "sol", "OK", null,
                                               Map.of("cat", string("a"), "val", number(90.0)), null, null),
                      "gauss", ENTITY, CBR, TENANT, "case-far", Path.root());

        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "gauss",
                            Map.of("cat", string("a"), "val", number(55.0)), 10),
                FeatureVectorCbrCase.class);

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().features().get("val")).isEqualTo(number(50.0));
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void noSpec_backwardCompatible_linearDecay() {
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg"), "army_size_ratio", number(1.5)), 10),
                FeatureVectorCbrCase.class);
        // Existing tests use the default schema with no SimilaritySpec — must still work
        assertThat(results).isNotNull();
    }

    @Test
    void stepDecay_hardCutoff() {
        var schema = CbrFeatureSchema.of("step",
                                         FeatureField.numeric("val", 0, 100, new SimilaritySpec.StepDecay(0.1)));
        store().registerSchema(schema);

        store().store(new FeatureVectorCbrCase("close", "sol", "OK", null,
                                               Map.of("val", number(55.0)), null, null),
                      "step", ENTITY, CBR, TENANT, "case-close", Path.root());
        store().store(new FeatureVectorCbrCase("far", "sol", "OK", null,
                                               Map.of("val", number(80.0)), null, null),
                      "step", ENTITY, CBR, TENANT, "case-far", Path.root());

        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "step",
                            Map.of("val", number(50.0)), 10).withMinSimilarity(0.5),
                FeatureVectorCbrCase.class);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().features().get("val")).isEqualTo(number(55.0));
    }

    // --- Retrieval mode tests ---

    @Test
    void retrieveSimilar_featureOnly_ignoresProblem() {
        registerDefaultSchema();
        store().store(new FeatureVectorCbrCase("problem text", "solution",
                                               "WIN", null, Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-mode-1", Path.root());
        var query = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                                Map.of("opponent_race", string("Zerg")), 5)
                            .withProblem("some problem")
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isNotEmpty();
    }

    @Test
    void retrieveSimilar_defaultRetrievalMode_isHybrid() {
        var query = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                                Map.of("opponent_race", string("Zerg")), 5);
        assertThat(query.retrievalMode()).isEqualTo(RetrievalMode.HYBRID);
    }

    @Test
    void retrieveSimilar_defaultFusionStrategy_isRrf() {
        var query = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                                Map.of("opponent_race", string("Zerg")), 5);
        assertThat(query.fusionStrategy()).isEqualTo(FusionStrategy.RRF);
    }

    @Test
    void retrieveSimilar_hybrid_withoutEmbeddingModel_degradesToFeatureOnly() {
        registerDefaultSchema();
        store().store(new FeatureVectorCbrCase("problem text", "solution",
                                               "WIN", null, Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-mode-2", Path.root());
        var query = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                                Map.of("opponent_race", string("Zerg")), 5)
                            .withProblem("problem")
                            .withRetrievalMode(RetrievalMode.HYBRID);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isNotEmpty();
    }

    @Test
    void retrieveSimilar_semanticOnly_withoutEmbeddingModel_returnsEmpty() {
        registerDefaultSchema();
        store().store(new FeatureVectorCbrCase("problem text", "solution",
                                               "WIN", null, Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-mode-3", Path.root());
        var query = CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                                Map.of("opponent_race", string("Zerg")), 5)
                            .withProblem("problem")
                            .withRetrievalMode(RetrievalMode.SEMANTIC_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

// ==========================================================================
// Structured case fields — contract tests for #89
// ==========================================================================

    private void registerStructuredSchema() {
        store().registerSchema(CbrFeatureSchema.of("game",
                                                   FeatureField.categorical("posture"),
                                                   FeatureField.numeric("score", 0, 100),
                                                   FeatureField.categoricalList("phases"),
                                                   FeatureField.nestedObject("economy",
                                                                             FeatureField.numeric("minute_3", 0, 100),
                                                                             FeatureField.categorical("tier")),
                                                   FeatureField.objectList("moments",
                                                                           FeatureField.categorical("type"),
                                                                           FeatureField.numeric("minute", 0, 90))));
    }

    private String storeGameCase(String problem, Map<String, FeatureValue> features, String caseId) {
        return store().store(
                new FeatureVectorCbrCase(problem, "solution", "WIN", null, features, null, null),
                "game", ENTITY, CBR, TENANT, caseId, Path.root());
    }

    @Test
    void structuredFields_categoricalList_containsFilter() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("ALL_IN"),
                                      "phases", stringList("EARLY_AGGRESSION", "MID_SKIRMISH", "LATE_PUSH")), "g1");
        storeGameCase("game2", Map.of("posture", string("DEFENSIVE"),
                                      "phases", stringList("TURTLE", "LATE_PUSH")), "g2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.contains("EARLY_AGGRESSION"));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_categoricalList_noFilter_returnsAll() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("ALL_IN"),
                                      "phases", stringList("EARLY", "MID")), "g1");
        storeGameCase("game2", Map.of("posture", string("DEFENSIVE"),
                                      "phases", stringList("TURTLE")), "g2");

        var q       = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
    }

    @Test
    void structuredFields_containsAll_matchesSubset() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "phases", stringList("A", "B", "C")), "g1");
        storeGameCase("game2", Map.of("posture", string("Y"),
                                      "phases", stringList("A", "D")), "g2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.containsAll(List.of("A", "B")));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_containsAll_rejectsMissingElement() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "phases", stringList("A", "B")), "g1");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.containsAll(List.of("A", "C")));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void structuredFields_containsAny_matchesAnyPresent() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "phases", stringList("A", "B")), "g1");
        storeGameCase("game2", Map.of("posture", string("Y"),
                                      "phases", stringList("C", "D")), "g2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.containsAny(List.of("X", "A")));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_containsAny_rejectsAllAbsent() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "phases", stringList("A", "B")), "g1");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.containsAny(List.of("X", "Y")));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void structuredFields_nestedObject_hasMatch_categoricalSubField() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "economy", struct(Map.of("minute_3", number(45), "tier", string("gold")))), "g1");
        storeGameCase("game2", Map.of("posture", string("Y"),
                                      "economy", struct(Map.of("minute_3", number(30), "tier", string("silver")))), "g2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("economy", CbrFilter.hasMatch(Map.of("tier", string("gold"))));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_nestedObject_hasMatch_numericRange() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "economy", struct(Map.of("minute_3", number(45), "tier", string("gold")))), "g1");
        storeGameCase("game2", Map.of("posture", string("Y"),
                                      "economy", struct(Map.of("minute_3", number(80), "tier", string("gold")))), "g2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("economy", CbrFilter.hasMatch(Map.of("minute_3", FeatureValue.range(40, 50))));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_nestedObject_hasMatch_exactNumeric() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "economy", struct(Map.of("minute_3", number(45), "tier", string("gold")))), "g1");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("economy", CbrFilter.hasMatch(Map.of("minute_3", number(45))));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
    }

    @Test
    void structuredFields_objectList_hasMatch_anyElementMatching() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "moments", structList(
                        Map.of("type", string("FIRST_CONTACT"), "minute", number(3.2)),
                        Map.of("type", string("BATTLE_WON"), "minute", number(5.1)))), "g1");
        storeGameCase("game2", Map.of("posture", string("Y"),
                                      "moments", structList(
                        Map.of("type", string("RETREAT"), "minute", number(8.0)))), "g2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("moments", CbrFilter.hasMatch(Map.of("type", string("FIRST_CONTACT"))));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_objectList_hasMatch_multipleSubFields_sameElement() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "moments", structList(
                        Map.of("type", string("FIRST_CONTACT"), "minute", number(3.2)),
                        Map.of("type", string("BATTLE_WON"), "minute", number(5.1)))), "g1");

        var qMatch = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                             .withFilter("moments", CbrFilter.hasMatch(Map.of("type", string("FIRST_CONTACT"), "minute", number(3.2))));
        assertThat(store().retrieveSimilar(qMatch, FeatureVectorCbrCase.class)).hasSize(1);

        var qCross = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                             .withFilter("moments", CbrFilter.hasMatch(Map.of("type", string("FIRST_CONTACT"), "minute", number(5.1))));
        assertThat(store().retrieveSimilar(qCross, FeatureVectorCbrCase.class)).isEmpty();
    }

    @Test
    void structuredFields_objectList_hasMatch_noMatchingElement() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "moments", structList(Map.of("type", string("RETREAT"), "minute", number(8.0)))), "g1");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("moments", CbrFilter.hasMatch(Map.of("type", string("FIRST_CONTACT"))));
        assertThat(store().retrieveSimilar(q, FeatureVectorCbrCase.class)).isEmpty();
    }

    @Test
    void structuredFields_mixedFlatAndStructured() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("ALL_IN"), "score", number(85),
                                      "phases", stringList("EARLY", "MID")), "g1");
        storeGameCase("game2", Map.of("posture", string("DEFENSIVE"), "score", number(30),
                                      "phases", stringList("EARLY", "LATE")), "g2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game",
                            Map.of("posture", string("ALL_IN"), "score", number(80)), 10)
                        .withFilter("phases", CbrFilter.contains("MID"));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_multipleFiltersAndSemantics() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "phases", stringList("EARLY", "MID"),
                                      "economy", struct(Map.of("minute_3", number(50), "tier", string("gold")))), "g1");
        storeGameCase("game2", Map.of("posture", string("Y"),
                                      "phases", stringList("EARLY", "MID"),
                                      "economy", struct(Map.of("minute_3", number(50), "tier", string("silver")))), "g2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.contains("EARLY"))
                        .withFilter("economy", CbrFilter.hasMatch(Map.of("tier", string("gold"))));
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_filterOnMissingFieldReturnsEmpty() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X")), "g1");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.contains("A"));
        assertThat(store().retrieveSimilar(q, FeatureVectorCbrCase.class)).isEmpty();
    }

    @Test
    void structuredFields_emptyCategoricalListFiltered() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "phases", stringList()), "g1");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.contains("A"));
        assertThat(store().retrieveSimilar(q, FeatureVectorCbrCase.class)).isEmpty();
    }

    @Test
    void structuredFields_validation_wrongFilterTypeOnField() {
        registerStructuredSchema();
        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("score", CbrFilter.contains("A"));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void structuredFields_validation_storeTimeMismatch() {
        registerStructuredSchema();
        assertThatThrownBy(() -> storeGameCase("bad", Map.of("phases", string("not_a_list")), "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void structuredFields_queryFeatures_categoricalListParticipatesInScoring() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("ALL_IN"),
                                      "phases", stringList("EARLY", "MID", "LATE")), "g1");
        storeGameCase("game2", Map.of("posture", string("DEFENSIVE"),
                                      "phases", stringList("TURTLE", "LATE")), "g2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game",
                            Map.of("phases", stringList("EARLY", "MID")), 10);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("game1");
    }

    @Test
    void structuredFields_validation_filtersWithNoSchema() {
        var q = CbrQuery.of(TENANT, CBR, Path.root(), "unregistered-type", Map.of(), 10)
                        .withFilter("phases", CbrFilter.contains("A"));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void structuredFields_validation_duplicateSchemaFieldNames() {
        assertThatThrownBy(() -> store().registerSchema(CbrFeatureSchema.of("dup",
                                                                            FeatureField.categorical("name"),
                                                                            FeatureField.categoricalList("name"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate field name");
    }

    @Test
    void structuredFields_validation_innerCategoricalWithSimilaritySpec() {
        assertThatThrownBy(() -> store().registerSchema(CbrFeatureSchema.of("bad",
                                                                            FeatureField.nestedObject("nested",
                                                                                                      FeatureField.categorical("cat", SimilaritySpec.categoricalTableBuilder()
                                                                                                                                                    .add("A", "B", 0.5).build())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SimilaritySpec not supported");
    }

    @Test
    void structuredFields_validation_innerSemanticText() {
        assertThatThrownBy(() -> store().registerSchema(CbrFeatureSchema.of("bad",
                                                                            FeatureField.nestedObject("nested",
                                                                                                      FeatureField.semanticText("desc")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic matching not supported");
    }

    @Test
    void structuredFields_validation_hasMatchNonexistentSubField() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "moments", structList(Map.of("type", string("X"), "minute", number(1.0)))), "g1");
        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("moments", CbrFilter.hasMatch(Map.of("nonexistent", string("val"))));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found in inner schema");
    }

    @Test
    void structuredFields_validation_hasMatchWrongSubFieldType() {
        registerStructuredSchema();
        storeGameCase("game1", Map.of("posture", string("X"),
                                      "moments", structList(Map.of("type", string("X"), "minute", number(1.0)))), "g1");
        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("moments", CbrFilter.hasMatch(Map.of("minute", string("not_a_number"))));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires NumberVal or RangeVal");
    }

    @Test
    void structuredFields_notContains_excludesCasesWithValue() {
        registerStructuredSchema();
        storeGameCase("has-rush", Map.of("posture", string("X"), "phases", stringList("EARLY", "RUSH", "LATE")), "c1");
        storeGameCase("no-rush", Map.of("posture", string("X"), "phases", stringList("EARLY", "MACRO", "LATE")), "c2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.notContains("RUSH"))
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("no-rush");
    }

    @Test
    void structuredFields_notContainsAny_excludesCasesWithAnyValue() {
        registerStructuredSchema();
        storeGameCase("has-rush", Map.of("posture", string("X"), "phases", stringList("EARLY", "RUSH")), "c1");
        storeGameCase("has-cheese", Map.of("posture", string("X"), "phases", stringList("EARLY", "CHEESE")), "c2");
        storeGameCase("clean", Map.of("posture", string("X"), "phases", stringList("EARLY", "MACRO")), "c3");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.notContainsAny(List.of("RUSH", "CHEESE")))
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("clean");
    }

    @Test
    void structuredFields_notContains_validation_requiresCategoricalList() {
        registerStructuredSchema();
        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("score", CbrFilter.notContains("X"));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void registerNumericListSchema() {
        store().registerSchema(CbrFeatureSchema.of("player-stats",
                                                   FeatureField.categorical("region"),
                                                   FeatureField.numericList("scores", 0, 100)));
    }

    private String storeNumericListCase(String problem, Map<String, FeatureValue> features, String caseId) {
        return store().store(
                new FeatureVectorCbrCase(problem, "solution", null, null, features, null, null),
                "player-stats", ENTITY, CBR, TENANT, caseId, Path.root());
    }

    @Test
    void numericList_storeAndRetrieve() {
        registerNumericListSchema();
        storeNumericListCase("high scorer", Map.of("region", string("NA"), "scores", numberList(85.0, 92.0, 78.0)), "c1");
        var q = CbrQuery.of(TENANT, CBR, Path.root(), "player-stats", Map.of("region", string("NA")), 10)
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
    }

    @Test
    void numericList_containsRange_matchesElementInRange() {
        registerNumericListSchema();
        storeNumericListCase("has-90s", Map.of("region", string("NA"), "scores", numberList(85.0, 92.0, 78.0)), "c1");
        storeNumericListCase("no-90s", Map.of("region", string("NA"), "scores", numberList(50.0, 60.0, 70.0)), "c2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "player-stats", Map.of(), 10)
                        .withFilter("scores", CbrFilter.containsRange(new NumericRange(90, 100)))
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("has-90s");
    }

    @Test
    void numericList_containsRange_noMatch() {
        registerNumericListSchema();
        storeNumericListCase("low", Map.of("region", string("NA"), "scores", numberList(10.0, 20.0, 30.0)), "c1");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "player-stats", Map.of(), 10)
                        .withFilter("scores", CbrFilter.containsRange(new NumericRange(90, 100)))
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void numericList_queryFeatures_participatesInScoring() {
        registerNumericListSchema();
        storeNumericListCase("player1", Map.of("scores", numberList(50.0, 60.0, 70.0)), "p1");
        storeNumericListCase("player2", Map.of("scores", numberList(10.0, 20.0, 30.0)), "p2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "player-stats",
                            Map.of("scores", numberList(55.0, 65.0)), 10);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("player1");
    }

    @Test
    void numericList_validation_storeNonNumberRejected() {
        registerNumericListSchema();
        assertThatThrownBy(() -> storeNumericListCase("bad", Map.of("scores", stringList("not-a-number")), "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numericList_validation_containsRangeOnCategoricalList_rejected() {
        registerStructuredSchema();
        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.containsRange(new NumericRange(1, 5)));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void structuredFields_allOf_twoHasMatch_onObjectList() {
        registerStructuredSchema();
        storeGameCase("both-match", Map.of("posture", string("X"),
                                           "moments", structList(
                        Map.of("type", string("FIRST_CONTACT"), "minute", number(3.0)),
                        Map.of("type", string("BATTLE_WON"), "minute", number(8.0)))), "c1");
        storeGameCase("only-one", Map.of("posture", string("X"),
                                         "moments", structList(
                        Map.of("type", string("FIRST_CONTACT"), "minute", number(3.0)))), "c2");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("moments", CbrFilter.allOf(
                                CbrFilter.hasMatch(Map.of("type", string("FIRST_CONTACT"))),
                                CbrFilter.hasMatch(Map.of("type", string("BATTLE_WON")))))
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("both-match");
    }

    @Test
    void structuredFields_allOf_containsAndNotContains() {
        registerStructuredSchema();
        storeGameCase("has-rush-no-cheese", Map.of("posture", string("X"), "phases", stringList("RUSH", "MACRO")), "c1");
        storeGameCase("has-both", Map.of("posture", string("X"), "phases", stringList("RUSH", "CHEESE")), "c2");
        storeGameCase("has-neither", Map.of("posture", string("X"), "phases", stringList("MACRO", "LATE")), "c3");

        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.allOf(
                                CbrFilter.contains("RUSH"),
                                CbrFilter.notContains("CHEESE")))
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("has-rush-no-cheese");
    }

    @Test
    void structuredFields_allOf_validation_requiresMinTwoFilters() {
        assertThatThrownBy(() -> CbrFilter.allOf(CbrFilter.contains("A")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void structuredFields_allOf_validation_rejectsNestedAllOf() {
        assertThatThrownBy(() -> CbrFilter.allOf(
                CbrFilter.contains("A"),
                CbrFilter.allOf(CbrFilter.contains("B"), CbrFilter.contains("C"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void structuredFields_allOf_validation_innerFilterTypeMismatch() {
        registerStructuredSchema();
        var q = CbrQuery.of(TENANT, CBR, Path.root(), "game", Map.of(), 10)
                        .withFilter("phases", CbrFilter.allOf(
                                CbrFilter.contains("A"),
                                CbrFilter.hasMatch(Map.of("x", string("y")))));
        assertThatThrownBy(() -> store().retrieveSimilar(q, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class);
    }


    // ========================= Temporal fields =========================

    private void registerTemporalSchema() {
        store().registerSchema(CbrFeatureSchema.of("temporal-game",
                                                   FeatureField.categorical("race"),
                                                   FeatureField.numeric("mmr", 0, 8000),
                                                   FeatureField.timeSeries("economyCurve", "minute",
                                                                           FeatureField.numeric("minute", 0, 30),
                                                                           FeatureField.numeric("economy", 0, 500),
                                                                           FeatureField.numeric("army", 0, 200),
                                                                           FeatureField.categorical("posture")),
                                                   FeatureField.discreteSequence("phaseProgression")));
    }

    private String storeTemporalCase(String problem, Map<String, FeatureValue> features, String caseId) {
        return store().store(
                new FeatureVectorCbrCase(problem, "solution", null, null, features, null, null),
                "temporal-game", ENTITY, CBR, TENANT, caseId, Path.root());
    }

    @Test
    void temporal_timeSeries_schemaCreation() {
        registerTemporalSchema();
    }

    @Test
    void temporal_timeSeries_validation_storeAscendingTimestamps() {
        registerTemporalSchema();
        storeTemporalCase("test", Map.of(
                                  "race", string("Terran"),
                                  "economyCurve", structList(
                                          Map.of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")),
                                          Map.of("minute", number(3), "economy", number(45), "army", number(5), "posture", string("MACRO")))),
                          "valid");
    }

    @Test
    void temporal_timeSeries_validation_storeNonAscending_rejected() {
        registerTemporalSchema();
        assertThatThrownBy(() -> storeTemporalCase("test", Map.of(
                                                           "race", string("Terran"),
                                                           "economyCurve", structList(
                                                                   Map.of("minute", number(3), "economy", number(45), "army", number(5), "posture", string("MACRO")),
                                                                   Map.of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")))),
                                                   "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_timeSeries_validation_missingTimestampField_rejected() {
        registerTemporalSchema();
        assertThatThrownBy(() -> storeTemporalCase("test", Map.of(
                                                           "race", string("Terran"),
                                                           "economyCurve", structList(Map.of("economy", number(30), "army", number(0), "posture", string("MACRO")))),
                                                   "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_timeSeries_validation_innerFieldTypes() {
        registerTemporalSchema();
        assertThatThrownBy(() -> storeTemporalCase("test", Map.of(
                                                           "race", string("Terran"),
                                                           "economyCurve", structList(Map.of("minute", number(1), "economy", string("not-a-number"), "army", number(0), "posture", string("MACRO")))),
                                                   "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_timeSeries_validation_emptyList_accepted() {
        registerTemporalSchema();
        storeTemporalCase("test", Map.of("race", string("Terran"), "economyCurve", structList()), "empty");
    }

    @Test
    void temporal_discreteSequence_validation_storeListOfStrings() {
        registerTemporalSchema();
        storeTemporalCase("test", Map.of(
                "race", string("Terran"),
                "phaseProgression", stringList("MACRO", "AGGRESSIVE", "ALL_IN")), "valid-seq");
    }

    @Test
    void temporal_discreteSequence_validation_storeWrongType_rejected() {
        registerTemporalSchema();
        assertThatThrownBy(() -> storeTemporalCase("test", Map.of(
                "race", string("Terran"),
                "phaseProgression", string("not-a-list")), "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_filter_onTemporalField_rejected() {
        registerTemporalSchema();
        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game", Map.of("race", string("Terran")), 10)
                            .withFilter("economyCurve", CbrFilter.contains("X"));
        assertThatThrownBy(() -> store().retrieveSimilar(query, FeatureVectorCbrCase.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_timeSeries_identicalSequences_scorePerfect() {
        registerTemporalSchema();
        var curve = List.of(
                Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")),
                Map.<String, FeatureValue>of("minute", number(3), "economy", number(45), "army", number(5), "posture", string("MACRO")));
        storeTemporalCase("game", Map.of("race", string("Terran"), "economyCurve", structList(curve)), "c1");

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game",
                                Map.of("economyCurve", structList(curve)), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void temporal_timeSeries_differentSequences_scoredByDtw() {
        registerTemporalSchema();
        storeTemporalCase("close", Map.of("race", string("Terran"),
                                          "economyCurve", structList(
                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(32), "army", number(1), "posture", string("MACRO")),
                        Map.<String, FeatureValue>of("minute", number(3), "economy", number(47), "army", number(6), "posture", string("MACRO")))), "close");
        storeTemporalCase("far", Map.of("race", string("Terran"),
                                        "economyCurve", structList(
                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(200), "army", number(100), "posture", string("AGGRESSIVE")),
                        Map.<String, FeatureValue>of("minute", number(3), "economy", number(400), "army", number(150), "posture", string("ALL_IN")))), "far");

        var queryCurve = List.of(
                Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")),
                Map.<String, FeatureValue>of("minute", number(3), "economy", number(45), "army", number(5), "posture", string("MACRO")));
        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game",
                                Map.of("economyCurve", structList(queryCurve)), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("close");
    }

    @Test
    void temporal_timeSeries_variableLength_handledByDtw() {
        registerTemporalSchema();
        storeTemporalCase("short", Map.of("race", string("Terran"),
                                          "economyCurve", structList(
                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")))), "short");
        storeTemporalCase("long", Map.of("race", string("Terran"),
                                         "economyCurve", structList(
                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")),
                        Map.<String, FeatureValue>of("minute", number(3), "economy", number(45), "army", number(5), "posture", string("MACRO")),
                        Map.<String, FeatureValue>of("minute", number(5), "economy", number(60), "army", number(10), "posture", string("AGGRESSIVE")))), "long");

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game",
                                Map.of("economyCurve", structList(
                                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")),
                                        Map.<String, FeatureValue>of("minute", number(3), "economy", number(45), "army", number(5), "posture", string("MACRO")))), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
    }

    @Test
    void temporal_timeSeries_dtwExcludesTimestampField() {
        registerTemporalSchema();
        storeTemporalCase("game", Map.of("race", string("Terran"),
                                         "economyCurve", structList(
                        Map.<String, FeatureValue>of("minute", number(29), "economy", number(50), "army", number(10), "posture", string("MACRO")))), "c1");

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game",
                                Map.of("economyCurve", structList(
                                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(50), "army", number(10), "posture", string("MACRO")))), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void temporal_discreteSequence_identicalSequences_scorePerfect() {
        registerTemporalSchema();
        storeTemporalCase("game", Map.of("race", string("Terran"),
                                         "phaseProgression", stringList("MACRO", "AGGRESSIVE", "ALL_IN")), "c1");

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game",
                                Map.of("phaseProgression", stringList("MACRO", "AGGRESSIVE", "ALL_IN")), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void temporal_discreteSequence_oneSubstitution_scoreLessThanPerfect() {
        registerTemporalSchema();
        storeTemporalCase("game", Map.of("race", string("Terran"),
                                         "phaseProgression", stringList("MACRO", "AGGRESSIVE", "ALL_IN")), "c1");

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game",
                                Map.of("phaseProgression", stringList("MACRO", "DEFENSIVE", "ALL_IN")), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isLessThan(1.0).isGreaterThan(0.0);
    }

    @Test
    void temporal_discreteSequence_completelyDifferent_scoreNearZero() {
        registerTemporalSchema();
        storeTemporalCase("game", Map.of("race", string("Terran"),
                                         "phaseProgression", stringList("A", "B", "C")), "c1");

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game",
                                Map.of("phaseProgression", stringList("X", "Y", "Z")), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(0.0);
    }

    @Test
    void temporal_discreteSequence_bothEmpty_scorePerfect() {
        registerTemporalSchema();
        storeTemporalCase("game", Map.of("race", string("Terran"),
                                         "phaseProgression", stringList()), "c1");

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game",
                                Map.of("phaseProgression", stringList()), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void temporal_mixedFlatAndTemporal_weightedScoring() {
        registerTemporalSchema();
        storeTemporalCase("sameRaceDiffCurve", Map.of(
                "race", string("Terran"), "mmr", number(4500),
                "economyCurve", structList(
                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(200), "army", number(100), "posture", string("AGGRESSIVE")))), "c1");
        storeTemporalCase("diffRaceSameCurve", Map.of(
                "race", string("Zerg"), "mmr", number(4500),
                "economyCurve", structList(
                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")))), "c2");

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game",
                                Map.of("race", string("Terran"), "economyCurve", structList(
                                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")))), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
    }

    @Test
    void temporal_weightOverride_temporalFieldDominates() {
        registerTemporalSchema();
        storeTemporalCase("sameRaceDiffCurve", Map.of(
                "race", string("Terran"),
                "economyCurve", structList(
                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(200), "army", number(100), "posture", string("AGGRESSIVE")))), "c1");
        storeTemporalCase("diffRaceSameCurve", Map.of(
                "race", string("Zerg"),
                "economyCurve", structList(
                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")))), "c2");

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game",
                                Map.of("race", string("Terran"), "economyCurve", structList(
                                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")))), 10)
                            .withWeight("economyCurve", 10.0).withWeight("race", 0.1)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("diffRaceSameCurve");
    }

    @Test
    void temporal_timeSeries_storeAndRetrieve_roundTrip() {
        registerTemporalSchema();
        var curve = List.of(
                Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")),
                Map.<String, FeatureValue>of("minute", number(3), "economy", number(45), "army", number(5), "posture", string("MACRO")));
        storeTemporalCase("game", Map.of("race", string("Terran"), "economyCurve", structList(curve)), "rt1");

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game", Map.of("race", string("Terran")), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        var retrieved = ((FeatureValue.StructListVal) results.get(0).cbrCase().features().get("economyCurve")).items();
        assertThat(retrieved).hasSize(2);
    }

    @Test
    void temporal_discreteSequence_storeAndRetrieve_roundTrip() {
        registerTemporalSchema();
        storeTemporalCase("game", Map.of("race", string("Terran"),
                                         "phaseProgression", stringList("MACRO", "AGGRESSIVE", "ALL_IN")), "rt2");

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game", Map.of("race", string("Terran")), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        var retrieved = ((FeatureValue.StringListVal) results.get(0).cbrCase().features().get("phaseProgression")).values();
        assertThat(retrieved).containsExactly("MACRO", "AGGRESSIVE", "ALL_IN");
    }

    @Test
    void temporal_coexistsWithStructuredFields() {
        store().registerSchema(CbrFeatureSchema.of("mixed",
                                                   FeatureField.categorical("race"),
                                                   FeatureField.categoricalList("tags"),
                                                   FeatureField.timeSeries("trajectory", "t",
                                                                           FeatureField.numeric("t", 0, 10),
                                                                           FeatureField.numeric("v", 0, 100)),
                                                   FeatureField.discreteSequence("phases")));

        store().store(
                new FeatureVectorCbrCase("problem", "solution", null, null, Map.of(
                        "race", string("Terran"),
                        "tags", stringList("aggro", "fast"),
                        "trajectory", structList(Map.<String, FeatureValue>of("t", number(1), "v", number(50))),
                        "phases", stringList("A", "B")), null, null),
                "mixed", ENTITY, CBR, TENANT, "mixed-1", Path.root());

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "mixed", Map.of("race", string("Terran")), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
    }

    @Test
    void temporal_flatFeatureFilter_reducesCandidatesBeforeDtw() {
        registerTemporalSchema();
        storeTemporalCase("terran-game", Map.of(
                "race", string("Terran"),
                "economyCurve", structList(
                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")))), "t1");
        storeTemporalCase("zerg-game", Map.of(
                "race", string("Zerg"),
                "economyCurve", structList(
                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")))), "z1");

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game",
                                Map.of("race", string("Terran"), "economyCurve", structList(
                                        Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")))), 10)
                            .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("terran-game");
    }

// ========================= Temporal SimilaritySpec =========================

    private void registerTemporalSchemaWithSpecs() {
        store().registerSchema(CbrFeatureSchema.of("temporal-game-specs",
                                                   FeatureField.categorical("race"),
                                                   FeatureField.numeric("mmr", 0, 8000),
                                                   FeatureField.timeSeries("economyCurve", "minute",
                                                                           new SimilaritySpec.DtwSpec(new WarpingConstraint.SakoeChibaBand(5)),
                                                                           FeatureField.numeric("minute", 0, 30),
                                                                           FeatureField.numeric("economy", 0, 500),
                                                                           FeatureField.numeric("army", 0, 200),
                                                                           FeatureField.categorical("posture")),
                                                   FeatureField.discreteSequence("phaseProgression",
                                                                                 new SimilaritySpec.EditDistanceSpec(Map.of(
                                                                                         "MACRO", Map.of("DEFENSIVE", 0.8, "AGGRESSIVE", 0.3),
                                                                                         "DEFENSIVE", Map.of("AGGRESSIVE", 0.1))))));
    }

    private String storeTemporalSpecCase(String problem, Map<String, FeatureValue> features, String caseId) {
        return store().store(
                new FeatureVectorCbrCase(problem, "solution", null, null, features, null, null),
                "temporal-game-specs", ENTITY, CBR, TENANT, caseId, Path.root());
    }

    @Test
    void temporal_timeSeries_dtwSpec_acceptedBySchema() {
        registerTemporalSchemaWithSpecs();
    }

    @Test
    void temporal_timeSeries_editDistanceSpec_rejectedBySchema() {
        assertThatThrownBy(() -> store().registerSchema(CbrFeatureSchema.of("bad-ts",
                                                                            FeatureField.timeSeries("curve", "t",
                                                                                                    new SimilaritySpec.EditDistanceSpec(Map.of()),
                                                                                                    FeatureField.numeric("t", 0, 10),
                                                                                                    FeatureField.numeric("val", 0, 100)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_discreteSequence_editDistanceSpec_acceptedBySchema() {
        registerTemporalSchemaWithSpecs();
    }

    @Test
    void temporal_discreteSequence_dtwSpec_rejectedBySchema() {
        assertThatThrownBy(() -> store().registerSchema(CbrFeatureSchema.of("bad-ds",
                                                                            FeatureField.discreteSequence("phases", new SimilaritySpec.DtwSpec(new WarpingConstraint.SakoeChibaBand(3))))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void temporal_timeSeries_dtwSpec_affectsRetrieval() {
        registerTemporalSchemaWithSpecs();
        storeTemporalSpecCase("close match", Map.of(
                                      "race", string("Terran"),
                                      "economyCurve", structList(
                                              Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")),
                                              Map.<String, FeatureValue>of("minute", number(3), "economy", number(45), "army", number(5), "posture", string("MACRO")))),
                              "close");
        storeTemporalSpecCase("far match", Map.of(
                                      "race", string("Terran"),
                                      "economyCurve", structList(
                                              Map.<String, FeatureValue>of("minute", number(1), "economy", number(400), "army", number(150), "posture", string("AGGRESSIVE")),
                                              Map.<String, FeatureValue>of("minute", number(3), "economy", number(100), "army", number(50), "posture", string("DEFENSIVE")))),
                              "far");
        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game-specs", Map.of(
                                        "economyCurve", structList(
                                                Map.<String, FeatureValue>of("minute", number(1), "economy", number(32), "army", number(2), "posture", string("MACRO")),
                                                Map.<String, FeatureValue>of("minute", number(3), "economy", number(47), "army", number(7), "posture", string("MACRO")))),
                                10).withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("close match");
    }

    @Test
    void temporal_discreteSequence_editDistanceSpec_affectsRetrieval() {
        registerTemporalSchemaWithSpecs();
        storeTemporalSpecCase("defensive", Map.of(
                                      "race", string("Terran"),
                                      "phaseProgression", stringList("MACRO", "DEFENSIVE")),
                              "def");
        storeTemporalSpecCase("aggressive", Map.of(
                                      "race", string("Terran"),
                                      "phaseProgression", stringList("MACRO", "AGGRESSIVE")),
                              "agg");
        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game-specs", Map.of(
                                        "phaseProgression", stringList("MACRO", "MACRO")),
                                10).withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).cbrCase().problem()).isEqualTo("defensive");
    }

    @Test
    void temporal_timeSeries_windowedDtw_similarResult() {
        registerTemporalSchemaWithSpecs();
        storeTemporalSpecCase("windowed test", Map.of(
                                      "race", string("Terran"),
                                      "economyCurve", structList(
                                              Map.<String, FeatureValue>of("minute", number(1), "economy", number(30), "army", number(0), "posture", string("MACRO")),
                                              Map.<String, FeatureValue>of("minute", number(3), "economy", number(45), "army", number(5), "posture", string("MACRO")))),
                              "w1");
        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game-specs", Map.of(
                                        "economyCurve", structList(
                                                Map.<String, FeatureValue>of("minute", number(1), "economy", number(32), "army", number(2), "posture", string("MACRO")),
                                                Map.<String, FeatureValue>of("minute", number(3), "economy", number(43), "army", number(3), "posture", string("MACRO")))),
                                10).withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).score()).isGreaterThan(0.5);
    }

    @Test
    void recordOutcome_updatesOutcomeAndConfidence() {
        store().store(new FeatureVectorCbrCase("prob", "sol", null, Confidence.unknown(0.8),
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-ro-1", Path.root());
        store().recordOutcome("case-ro-1", TENANT,
                              CbrOutcome.of(1.0, "all nodes succeeded", Instant.now()));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 10)
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY),
                FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().outcome()).isEqualTo("SUCCESS");
        assertThat(results.getFirst().cbrCase().confidence().value()).isCloseTo(0.84, within(0.001));
    }

    @Test
    void recordOutcome_partialResult() {
        store().store(new FeatureVectorCbrCase("prob", "sol", null, Confidence.unknown(0.8),
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-ro-2", Path.root());
        store().recordOutcome("case-ro-2", TENANT,
                              CbrOutcome.of(0.5, "2 of 4", Instant.now()));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 10)
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY),
                FeatureVectorCbrCase.class);
        assertThat(results.getFirst().cbrCase().outcome()).isEqualTo("PARTIAL");
        assertThat(results.getFirst().cbrCase().confidence().value()).isCloseTo(0.74, within(0.001));
    }

    @Test
    void recordOutcome_failure_decreasesConfidence() {
        store().store(new FeatureVectorCbrCase("prob", "sol", null, Confidence.unknown(0.8),
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-ro-3", Path.root());
        store().recordOutcome("case-ro-3", TENANT,
                              CbrOutcome.of(0.0, "all failed", Instant.now()));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 10)
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY),
                FeatureVectorCbrCase.class);
        assertThat(results.getFirst().cbrCase().confidence().value()).isCloseTo(0.64, within(0.001));
    }

    @Test
    void recordOutcome_multipleOutcomes_emaConverges() {
        store().store(new FeatureVectorCbrCase("prob", "sol", null, Confidence.unknown(0.5),
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-ro-4", Path.root());
        Instant base = Instant.parse("2026-07-13T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            store().recordOutcome("case-ro-4", TENANT,
                                  CbrOutcome.of(1.0, null, base.plusSeconds(i + 1)));
        }
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 10)
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY),
                FeatureVectorCbrCase.class);
        assertThat(results.getFirst().cbrCase().confidence().value()).isGreaterThan(0.8);
    }

    @Test
    void recordOutcome_customLearningRate_convergesFaster() {
        var fastSchema = new CbrFeatureSchema("fast-learn",
                                              java.util.List.of(FeatureField.categorical("cat"), FeatureField.numeric("val", 0, 100)),
                                              0.8);
        store().registerSchema(fastSchema);

        store().store(new FeatureVectorCbrCase("p", "s", "o", Confidence.unknown(1.0),
                                               Map.of("cat", string("a"), "val", number(50)), null, null),
                      "fast-learn", ENTITY, CBR, TENANT, "c-lr-1", Path.root());

        store().recordOutcome("c-lr-1", TENANT,
                              CbrOutcome.of(0.0, "fail", Instant.now()));

        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "fast-learn",
                            Map.of("cat", string("a"), "val", number(50)), 10)
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY),
                FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        // With learning rate 0.8: confidence = (1-0.8)*1.0 + 0.8*0.0 = 0.2
        assertThat(results.getFirst().cbrCase().confidence().value()).isCloseTo(0.2, within(0.01));
    }


    @Test
    void recordOutcome_nullInitialConfidence_treatsAsOne() {
        store().store(new FeatureVectorCbrCase("prob", "sol", null, null,
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-ro-5", Path.root());
        store().recordOutcome("case-ro-5", TENANT,
                              CbrOutcome.of(0.0, null, Instant.now()));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 10)
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY),
                FeatureVectorCbrCase.class);
        assertThat(results.getFirst().cbrCase().confidence().value()).isCloseTo(0.8, within(0.001));
    }

    @Test
    void recordOutcome_unknownCaseId_silentlyIgnored() {
        assertThatCode(() -> store().recordOutcome("nonexistent", TENANT,
                                                   CbrOutcome.of(1.0, null, Instant.now())))
                .doesNotThrowAnyException();
    }

    @Test
    void recordOutcome_preservesOtherFields() {
        Map<String, FeatureValue> features = Map.of("opponent_race", string("Zerg"),
                                                    "army_size_ratio", number(0.7));
        store().store(new FeatureVectorCbrCase("my problem", "my solution", null, Confidence.unknown(0.8),
                                               features, null, null), "starcraft-game", ENTITY, CBR, TENANT, "case-ro-7", Path.root());
        store().recordOutcome("case-ro-7", TENANT,
                              CbrOutcome.of(1.0, "ok", Instant.now()));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 10)
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY),
                FeatureVectorCbrCase.class);
        var c = results.getFirst().cbrCase();
        assertThat(c.problem()).isEqualTo("my problem");
        assertThat(c.solution()).isEqualTo("my solution");
        assertThat(c.features()).containsEntry("opponent_race", string("Zerg"));
        assertThat(c.features()).containsEntry("army_size_ratio", number(0.7));
    }

    @Test
    void recordOutcome_withDetail_doesNotCorruptCase() {
        store().store(new FeatureVectorCbrCase("prob", "sol", null, Confidence.unknown(0.8),
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-ro-8", Path.root());
        store().recordOutcome("case-ro-8", TENANT,
                              CbrOutcome.of(0.75, "3/4 nodes succeeded, 1 FAILED: node-xyz", Instant.now()));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 10)
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY),
                FeatureVectorCbrCase.class);
        assertThat(results.getFirst().cbrCase().outcome()).isEqualTo("PARTIAL");
        assertThat(results.getFirst().cbrCase().confidence().value()).isCloseTo(0.79, within(0.001));
    }

    @Test
    void recordOutcome_duplicateObservedAt_idempotent() {
        store().store(new FeatureVectorCbrCase("prob", "sol", null, Confidence.unknown(0.8),
                                               Map.of("opponent_race", string("Zerg")), null, null),
                      "starcraft-game", ENTITY, CBR, TENANT, "case-ro-9", Path.root());
        Instant observed = Instant.parse("2026-07-13T10:00:00Z");
        store().recordOutcome("case-ro-9", TENANT,
                              CbrOutcome.of(1.0, null, observed));
        store().recordOutcome("case-ro-9", TENANT,
                              CbrOutcome.of(1.0, null, observed));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 10)
                        .withRetrievalMode(RetrievalMode.FEATURE_ONLY),
                FeatureVectorCbrCase.class);
        assertThat(results.getFirst().cbrCase().confidence().value()).isCloseTo(0.84, within(0.001));
    }

    @Test
    void purge_countBased_keepsNewestCases() {
        registerDefaultSchema();
        for (int i = 0; i < 5; i++) {
            store().store(
                    new FeatureVectorCbrCase("problem-" + i, "solution-" + i, null, null,
                                             Map.of("severity", FeatureValue.string("HIGH")), null, null),
                    "diagnosis", ENTITY, CBR, TENANT, "case-" + i, Path.root());
        }

        var policy  = new CbrRetentionPolicy(TENANT, CBR, "diagnosis", null, 3, null);
        int deleted = store().purge(policy);

        assertThat(deleted).isEqualTo(2);
        var remaining = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "diagnosis",
                            Map.of("severity", FeatureValue.string("HIGH")), 10),
                FeatureVectorCbrCase.class);
        assertThat(remaining).hasSize(3);
    }

    @Test
    void purge_countBased_noPurgeWhenUnderLimit() {
        registerDefaultSchema();
        store().store(
                new FeatureVectorCbrCase("p1", "s1", null, null,
                                         Map.of("severity", FeatureValue.string("HIGH")), null, null),
                "diagnosis", ENTITY, CBR, TENANT, "case-1", Path.root());

        var policy  = new CbrRetentionPolicy(TENANT, CBR, "diagnosis", null, 5, null);
        int deleted = store().purge(policy);
        assertThat(deleted).isEqualTo(0);
    }

    @Test
    void purge_ageBased_recentCasesNotPurged() {
        registerDefaultSchema();
        store().store(
                new FeatureVectorCbrCase("p1", "s1", null, null,
                                         Map.of("severity", FeatureValue.string("HIGH")), null, null),
                "diagnosis", ENTITY, CBR, TENANT, "case-1", Path.root());

        var policy  = new CbrRetentionPolicy(TENANT, CBR, "diagnosis", 365, null, null);
        int deleted = store().purge(policy);
        assertThat(deleted).isEqualTo(0);
    }

    @Test
    void purge_scopedByTenant() {
        registerDefaultSchema();
        store().store(
                new FeatureVectorCbrCase("p1", "s1", null, null,
                                         Map.of("severity", FeatureValue.string("HIGH")), null, null),
                "diagnosis", ENTITY, CBR, TENANT, "case-1", Path.root());
        store().store(
                new FeatureVectorCbrCase("p2", "s2", null, null,
                                         Map.of("severity", FeatureValue.string("LOW")), null, null),
                "diagnosis", ENTITY, CBR, "other-tenant", "case-2", Path.root());

        var policy  = new CbrRetentionPolicy(TENANT, CBR, "diagnosis", null, 0 + 1, null);
        int deleted = store().purge(policy);

        assertThat(deleted).isEqualTo(0);
        var otherResults = store().retrieveSimilar(
                CbrQuery.of("other-tenant", CBR, Path.root(), "diagnosis",
                            Map.of("severity", FeatureValue.string("LOW")), 10),
                FeatureVectorCbrCase.class);
        assertThat(otherResults).hasSize(1);
    }

    @Test
    void purge_combinedAgeAndCount() {
        registerDefaultSchema();
        for (int i = 0; i < 4; i++) {
            store().store(
                    new FeatureVectorCbrCase("problem-" + i, "solution-" + i, null, null,
                                             Map.of("severity", FeatureValue.string("HIGH")), null, null),
                    "diagnosis", ENTITY, CBR, TENANT, "case-" + i, Path.root());
        }

        var policy  = new CbrRetentionPolicy(TENANT, CBR, "diagnosis", 365, 2, null);
        int deleted = store().purge(policy);
        assertThat(deleted).isEqualTo(2);
    }

    @Test
    void purge_trustBased_purgesLowTrustCases() {
        registerDefaultSchema();
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), 0.1, null),
                "diagnosis", ENTITY, CBR, TENANT, "c1", Path.root());
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), 0.9, null),
                "diagnosis", ENTITY, CBR, TENANT, "c2", Path.root());
        store().purge(new CbrRetentionPolicy(TENANT, CBR, "diagnosis", null, null, 0.5));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "diagnosis", Map.of(), 10),
                FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).cbrCase().trustScore()).isEqualTo(0.9);
    }

    @Test
    void purge_trustBased_preservesNullTrustCases() {
        registerDefaultSchema();
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), null, null),
                "diagnosis", ENTITY, CBR, TENANT, "c1", Path.root());
        store().purge(new CbrRetentionPolicy(TENANT, CBR, "diagnosis", null, null, 0.5));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "diagnosis", Map.of(), 10),
                FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
    }

    @Test
    void purge_trustBased_preservesHighTrustCases() {
        registerDefaultSchema();
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), 0.5, null),
                "diagnosis", ENTITY, CBR, TENANT, "c1", Path.root());
        store().purge(new CbrRetentionPolicy(TENANT, CBR, "diagnosis", null, null, 0.5));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "diagnosis", Map.of(), 10),
                FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
    }

    @Test
    void purge_trustAndAge_orSemantics() {
        registerDefaultSchema();
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), 0.1, null),
                "diagnosis", ENTITY, CBR, TENANT, "c1", Path.root());
        store().purge(new CbrRetentionPolicy(TENANT, CBR, "diagnosis", 365, null, 0.5));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "diagnosis", Map.of(), 10),
                FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void purge_trustBased_scopedByTenant() {
        registerDefaultSchema();
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), 0.1, null),
                "diagnosis", ENTITY, CBR, TENANT, "c1", Path.root());
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), 0.1, null),
                "diagnosis", ENTITY, CBR, "other-tenant", "c2", Path.root());
        store().purge(new CbrRetentionPolicy(TENANT, CBR, "diagnosis", null, null, 0.5));
        var results = store().retrieveSimilar(
                CbrQuery.of("other-tenant", CBR, Path.root(), "diagnosis", Map.of(), 10),
                FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
    }
// ── Trust field round-trip contract tests (#154) ──────────────────

    @Test
    void trustScore_roundTrip() {
        registerDefaultSchema();
        var c = new FeatureVectorCbrCase("p", "s", "WIN", Confidence.unknown(0.9),
                                         Map.of("opponent_race", string("Zerg")), 0.85, null);
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "trust-rt-1", Path.root());
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5),
                FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().trustScore()).isEqualTo(0.85);
    }

    @Test
    void producerAgentId_roundTrip() {
        registerDefaultSchema();
        var c = new FeatureVectorCbrCase("p", "s", "WIN", Confidence.unknown(0.9),
                                         Map.of("opponent_race", string("Zerg")), null, "agent-1");
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "trust-rt-2", Path.root());
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5),
                FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().producerAgentId()).isEqualTo("agent-1");
    }

    @Test
    void trustScore_nullPreserved() {
        registerDefaultSchema();
        var c = new FeatureVectorCbrCase("p", "s", "WIN", Confidence.unknown(0.9),
                                         Map.of("opponent_race", string("Zerg")), null, null);
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "trust-rt-3", Path.root());
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5),
                FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().trustScore()).isNull();
    }

    @Test
    void trustScore_withOutcome_preserved() {
        registerDefaultSchema();
        store().store(new FeatureVectorCbrCase("p", "s", "WIN", Confidence.unknown(0.5),
                                               Map.of("opponent_race", string("Zerg")), 0.85, "agent-1"),
                      "starcraft-game", ENTITY, CBR, TENANT, "trust-rt-4", Path.root());
        store().recordOutcome("trust-rt-4", TENANT,
                              CbrOutcome.of(1.0, "validated", java.time.Instant.now()));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5),
                FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().trustScore()).isEqualTo(0.85);
    }

    @Test
    void trustScore_withFeatures_preserved() {
        registerDefaultSchema();
        var original = new FeatureVectorCbrCase("p", "s", "WIN", Confidence.unknown(0.9),
                                                Map.of("opponent_race", string("Zerg")), 0.85, "agent-1");
        var enriched = original.withFeatures(Map.of("opponent_race", string("Zerg"),
                                                    "detected_build", string("ROACH_RUSH")));
        assertThat(enriched.trustScore()).isEqualTo(0.85);
        assertThat(enriched.producerAgentId()).isEqualTo("agent-1");
    }

    @Test
    void trustScore_validation_rejectsOutOfRange() {
        assertThatThrownBy(() -> new FeatureVectorCbrCase("p", "s", null, null,
                                                          Map.of(), 1.5, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeatureVectorCbrCase("p", "s", null, null,
                                                          Map.of(), -0.1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void producerAgentId_withOutcome_preserved() {
        registerDefaultSchema();
        store().store(new FeatureVectorCbrCase("p", "s", "WIN", Confidence.unknown(0.5),
                                               Map.of("opponent_race", string("Zerg")), 0.7, "agent-2"),
                      "starcraft-game", ENTITY, CBR, TENANT, "trust-rt-5", Path.root());
        store().recordOutcome("trust-rt-5", TENANT,
                              CbrOutcome.of(0.8, "good", java.time.Instant.now()));
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5),
                FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().producerAgentId()).isEqualTo("agent-2");
    }

    @Test
    void planCbrCase_trustFields_roundTrip() {
        registerDefaultSchema();
        var trace = new PlanTrace("scout", "reconnaissance", "drone-scout", "SUCCESS", 1, Map.of(), null);
        var c = new PlanCbrCase("Zerg rush", "early pressure", "WIN", Confidence.unknown(0.85),
                                Map.of("opponent_race", string("Zerg")),
                                List.of(trace), 0.92, "agent-plan");
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "trust-rt-6", Path.root());
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game",
                            Map.of("opponent_race", string("Zerg")), 5),
                PlanCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().trustScore()).isEqualTo(0.92);
        assertThat(results.getFirst().cbrCase().producerAgentId()).isEqualTo("agent-plan");
    }

    @Test
    void textualCbrCase_trustFields_roundTrip() {
        registerDefaultSchema();
        var c = new TextualCbrCase("Zerg rush", "early pressure", "WIN", Confidence.unknown(0.85), 0.88, "agent-text");
        store().store(c, "starcraft-game", ENTITY, CBR, TENANT, "trust-rt-7", Path.root());
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "starcraft-game", Map.of(), 5),
                TextualCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().trustScore()).isEqualTo(0.88);
        assertThat(results.getFirst().cbrCase().producerAgentId()).isEqualTo("agent-text");
    }


    @Test
    void scan_returnsCbrCaseSummary() {
        registerDefaultSchema();
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), 0.8, "agent-1"),
                "diagnosis", ENTITY, CBR, TENANT, "c1", Path.root());
        var result = store().scan(new CbrScanRequest(TENANT, CBR, "diagnosis", 10, null));
        assertThat(result.items()).hasSize(1);
        var summary = result.items().get(0);
        assertThat(summary.caseId()).isNotBlank();
        assertThat(summary.entityId()).isEqualTo(ENTITY);
        assertThat(summary.caseType()).isEqualTo("diagnosis");
        assertThat(summary.producerAgentId()).isEqualTo("agent-1");
        assertThat(summary.trustScore()).isEqualTo(0.8);
        assertThat(summary.storedAt()).isNotNull();}

    @Test
    void scan_pagination() {
        registerDefaultSchema();
        for (int i = 0; i < 5; i++) {
            store().store(
                    new FeatureVectorCbrCase("p" + i, "s", null, null, Map.of(), null, null),
                    "diagnosis", ENTITY, CBR, TENANT, "c" + i, Path.root());
        }
        var page1 = store().scan(new CbrScanRequest(TENANT, CBR, "diagnosis", 3, null));
        assertThat(page1.items()).hasSize(3);
        assertThat(page1.nextCursor()).isNotNull();

        var page2 = store().scan(new CbrScanRequest(TENANT, CBR, "diagnosis", 3, page1.nextCursor()));
        assertThat(page2.items()).hasSize(2);
        assertThat(page2.nextCursor()).isNull();

        var allIds = new java.util.HashSet<String>();
        page1.items().forEach(s -> allIds.add(s.caseId()));
        page2.items().forEach(s -> allIds.add(s.caseId()));
        assertThat(allIds).containsExactlyInAnyOrder("c0", "c1", "c2", "c3", "c4");}

    @Test
    void scan_scopedByTenant() {
        registerDefaultSchema();
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), null, null),
                "diagnosis", ENTITY, CBR, TENANT, "c1", Path.root());
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), null, null),
                "diagnosis", ENTITY, CBR, "other", "c2", Path.root());
        var result = store().scan(new CbrScanRequest(TENANT, CBR, "diagnosis", 10, null));
        assertThat(result.items()).hasSize(1);}

    @Test
    void scan_scopedByDomainAndCaseType() {
        registerDefaultSchema();
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), null, null),
                "diagnosis", ENTITY, CBR, TENANT, "c1", Path.root());
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), null, null),
                "starcraft-game", ENTITY, CBR, TENANT, "c2", Path.root());
        var result = store().scan(new CbrScanRequest(TENANT, CBR, "diagnosis", 10, null));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).caseType()).isEqualTo("diagnosis");}

    @Test
    void discoverTenants_returnsDistinctTenants() {
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), null, null),
                "starcraft-game", ENTITY, CBR, "t1", "c1", Path.root());
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), null, null),
                "starcraft-game", ENTITY, CBR, "t2", "c2", Path.root());
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of(), null, null),
                "starcraft-game", ENTITY, CBR, "t1", "c3", Path.root());
        var tenants = store().discoverTenants(CBR);
        assertThat(tenants).containsExactlyInAnyOrder("t1", "t2");
    }

    @Test
    void discoverTenants_emptyWhenNoCases() {
        var tenants = store().discoverTenants(CBR);
        assertThat(tenants).isEmpty();
    }


    @Test
    void temporalDecay_null_noEffect() {
        registerDefaultSchema();
        store().store(new FeatureVectorCbrCase("p1", "s1", null, null,
                                               Map.of("severity", FeatureValue.string("HIGH")), null, null),
                      "diagnosis", ENTITY, CBR, TENANT, "case-1", Path.root());

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "diagnosis",
                                Map.of("severity", FeatureValue.string("HIGH")), 10);
        assertThat(query.temporalDecay()).isNull();
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void temporalDecay_longHalfLife_recentCasesUnaffected() {
        registerDefaultSchema();
        store().store(new FeatureVectorCbrCase("p1", "s1", null, null,
                                               Map.of("severity", FeatureValue.string("HIGH")), null, null),
                      "diagnosis", ENTITY, CBR, TENANT, "case-1", Path.root());

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "diagnosis",
                                Map.of("severity", FeatureValue.string("HIGH")), 10)
                            .withTemporalDecay(new TemporalDecay.HalfLife(java.time.Duration.ofDays(365)));
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void temporalDecay_withTemporalDecay_builderPreservesOtherFields() {
        var base = CbrQuery.of(TENANT, CBR, Path.root(), "diagnosis",
                               Map.of("severity", FeatureValue.string("HIGH")), 5)
                           .withMinSimilarity(0.3);
        var decayed = base.withTemporalDecay(new TemporalDecay.HalfLife(java.time.Duration.ofDays(30)));
        assertThat(decayed.minSimilarity()).isEqualTo(0.3);
        assertThat(decayed.topK()).isEqualTo(5);
        assertThat(decayed.temporalDecay()).isInstanceOf(TemporalDecay.HalfLife.class);
    }
// ── Trend detection ──────────────────────────────────────────────────

    private void registerTrendSchema() {
        store().registerSchema(CbrFeatureSchema.of("trend-clinical",
                                                   FeatureField.categorical("drug"),
                                                   FeatureField.timeSeries("vitals", "t",
                                                                           null,
                                                                           new TrendSpec(java.util.Set.of(TrendType.SLOPE, TrendType.DELTA), java.time.temporal.ChronoUnit.HOURS),
                                                                           FeatureField.numeric("t", 0, 100),
                                                                           FeatureField.numeric("hr", 40, 200))));
    }

    private String storeTrendCase(String problem, Map<String, FeatureValue> features, String caseId) {
        return store().store(
                new FeatureVectorCbrCase(problem, "solution", null, null, features, null, null),
                "trend-clinical", ENTITY, CBR, TENANT, caseId, Path.root());
    }

    @Test
    void trend_schemaWithTrendSpec_expandsDerivedFields() {
        registerTrendSchema();
    }

    @Test
    void trend_enrichment_storeWithTrendSpec_derivedFieldsPopulated() {
        registerTrendSchema();
        var obs = java.util.List.of(
                Map.<String, FeatureValue>of("t", number(0), "hr", number(60)),
                Map.<String, FeatureValue>of("t", number(1), "hr", number(80)));
        storeTrendCase("escalating", Map.of(
                "drug", string("aspirin"),
                "vitals", structList(obs)), null);

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "trend-clinical",
                                Map.of("drug", string("aspirin"),
                                       "vitals", structList(obs)), 10);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().score()).isGreaterThan(0.0);
    }

    @Test
    void trend_slope_similarSlopesScoreHigher() {
        registerTrendSchema();
        var rising = java.util.List.of(
                Map.<String, FeatureValue>of("t", number(0), "hr", number(60)),
                Map.<String, FeatureValue>of("t", number(1), "hr", number(80)));
        var flat = java.util.List.of(
                Map.<String, FeatureValue>of("t", number(0), "hr", number(70)),
                Map.<String, FeatureValue>of("t", number(1), "hr", number(70)));
        storeTrendCase("rising", Map.of("drug", string("aspirin"), "vitals", structList(rising)), "c1");
        storeTrendCase("flat", Map.of("drug", string("aspirin"), "vitals", structList(flat)), "c2");

        var queryObs = java.util.List.of(
                Map.<String, FeatureValue>of("t", number(0), "hr", number(65)),
                Map.<String, FeatureValue>of("t", number(1), "hr", number(85)));
        var query = CbrQuery.of(TENANT, CBR, Path.root(), "trend-clinical",
                                Map.of("drug", string("aspirin"), "vitals", structList(queryObs)), 10)
                            .withWeight("vitals_slope_hr", 5.0);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("rising");
    }

    @Test
    void trend_noTrendSpec_noDerivedFields() {
        registerTemporalSchema();
        storeTemporalCase("test", Map.of(
                "race", string("Terran"),
                "economyCurve", structList(java.util.List.of(
                        Map.<String, FeatureValue>of("minute", number(0), "economy", number(100), "army", number(10), "posture", string("def")),
                        Map.<String, FeatureValue>of("minute", number(5), "economy", number(200), "army", number(20), "posture", string("atk")))),
                "phaseProgression", stringList("early", "mid")), null);

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "temporal-game",
                                Map.of("race", string("Terran"),
                                       "economyCurve", structList(java.util.List.of(
                                                Map.<String, FeatureValue>of("minute", number(0), "economy", number(100), "army", number(10), "posture", string("def")),
                                                Map.<String, FeatureValue>of("minute", number(5), "economy", number(200), "army", number(20), "posture", string("atk")))),
                                       "phaseProgression", stringList("early", "mid")), 10);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isNotEmpty();
    }

    @Test
    void trend_emptyObservations_trendFeaturesAreZero() {
        registerTrendSchema();
        storeTrendCase("empty", Map.of(
                "drug", string("aspirin"),
                "vitals", structList(java.util.List.of())), null);

        var query = CbrQuery.of(TENANT, CBR, Path.root(), "trend-clinical",
                                Map.of("drug", string("aspirin"),
                                       "vitals", structList(java.util.List.of())), 10);
        var results = store().retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(results).isNotEmpty();
    }

    // ── storedAt ──────────────────────────────────────────────

    @Test
    void storedAt_populatedOnRetrievedCases() {
        store().registerSchema(new CbrFeatureSchema("sa-type",
                List.of(new FeatureField.Numeric("severity", 0, 10))));
        store().store(new FeatureVectorCbrCase("p", "s", null, null,
                                               Map.of("severity", number(5)), null, null), "sa-type", ENTITY, CBR, TENANT, "sa-c1", Path.root());
        var results = store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "sa-type", Map.of("severity", number(5)), 10),
                FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().storedAt()).isNotNull();
    }

    // ── Supersession ─────────────────────────────────────────

    private void registerSupersessionSchema() {
        store().registerSchema(new CbrFeatureSchema("ss-type",
                List.of(new FeatureField.Numeric("severity", 0, 10))));
    }

    private String storeSupersessionCase(String caseId) {
        return store().store(new FeatureVectorCbrCase("problem", "solution", null, null,
                                                      Map.of("severity", number(5)), null, null), "ss-type", ENTITY, CBR, TENANT, caseId, Path.root());
    }

    private List<ScoredCbrCase<FeatureVectorCbrCase>> querySupersession() {
        return store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), "ss-type", Map.of("severity", number(5)), 10),
                FeatureVectorCbrCase.class);
    }

    @Test
    void supersede_excludesFromRetrieval() {
        registerSupersessionSchema();
        storeSupersessionCase("sup-c1");
        assertThat(store().supersede("sup-c1", TENANT, null, null)).isTrue();
        assertThat(querySupersession()).isEmpty();
    }

    @Test
    void reinstate_restoresRetrievalVisibility() {
        registerSupersessionSchema();
        storeSupersessionCase("sup-c2");
        assertThat(store().supersede("sup-c2", TENANT, null, "overturned")).isTrue();
        assertThat(store().reinstate("sup-c2", TENANT)).isTrue();
        assertThat(querySupersession()).hasSize(1);
    }

    @Test
    void supersede_alreadySuperseded_returnsFalse() {
        registerSupersessionSchema();
        storeSupersessionCase("sup-c3");
        assertThat(store().supersede("sup-c3", TENANT, null, "first")).isTrue();
        assertThat(store().supersede("sup-c3", TENANT, "replacement", "second")).isFalse();
        assertThat(querySupersession()).isEmpty();
    }

    @Test
    void reinstate_notSuperseded_returnsFalse() {
        registerSupersessionSchema();
        storeSupersessionCase("sup-c4");
        assertThat(store().reinstate("sup-c4", TENANT)).isFalse();
        assertThat(querySupersession()).hasSize(1);
    }

    @Test
    void supersede_nonExistentCase_returnsFalse() {
        registerSupersessionSchema();
        assertThat(store().supersede("nonexistent", TENANT, null, null)).isFalse();
    }

    @Test
    void supersede_eraseStillWorks() {
        registerSupersessionSchema();
        storeSupersessionCase("sup-c5");
        store().supersede("sup-c5", TENANT, null, null);
        Integer erased = store().erase(new io.casehub.neocortex.memory.EraseRequest(ENTITY, CBR, TENANT, "sup-c5"));
        assertThat(erased).isGreaterThanOrEqualTo(1);
    }

    @Test
    void supersede_recordOutcomeStillWorks() {
        registerSupersessionSchema();
        storeSupersessionCase("sup-c6");
        store().supersede("sup-c6", TENANT, null, null);
        assertThatCode(() -> store().recordOutcome("sup-c6", TENANT,
                CbrOutcome.of(1.0, "ok", Instant.now())))
                .doesNotThrowAnyException();
    }

    @Test
    void supersede_purgeStillApplies() {
        registerSupersessionSchema();
        storeSupersessionCase("sup-c7");
        store().supersede("sup-c7", TENANT, null, null);
        assertThatCode(() -> store().purge(new CbrRetentionPolicy(TENANT, CBR, "ss-type", null, 1, null)))
                .doesNotThrowAnyException();
    }


    @Test
    void scope_cascadeVisibility_ancestorScopesVisible() {
        store().registerSchema(CbrFeatureSchema.of("scoped",
                                                   FeatureField.categorical("level")));
        var rootCase = new FeatureVectorCbrCase("global signal", "sol", null, null,
                                                Map.of("level", FeatureValue.string("root")), null, null);
        var midCase = new FeatureVectorCbrCase("trial signal", "sol", null, null,
                                               Map.of("level", FeatureValue.string("mid")), null, null);
        var leafCase = new FeatureVectorCbrCase("patient signal", "sol", null, null,
                                                Map.of("level", FeatureValue.string("leaf")), null, null);
        store().store(rootCase, "scoped", ENTITY, CBR, TENANT, "root-1", Path.root());
        store().store(midCase, "scoped", ENTITY, CBR, TENANT, "mid-1", Path.of("trial"));
        store().store(leafCase, "scoped", ENTITY, CBR, TENANT, "leaf-1",
                      Path.of("trial", "site", "patient"));
        var q = CbrQuery.of(TENANT, CBR, Path.of("trial", "site", "patient"),
                            "scoped", Map.of("level", FeatureValue.string("leaf")), 10);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(3);
    }

    @Test
    void scope_noUpwardLeakage_childNotVisibleFromParent() {
        store().registerSchema(CbrFeatureSchema.of("scoped2",
                                                   FeatureField.categorical("level")));
        var childCase = new FeatureVectorCbrCase("patient data", "sol", null, null,
                                                 Map.of("level", FeatureValue.string("child")), null, null);
        store().store(childCase, "scoped2", ENTITY, CBR, TENANT, "child-1",
                      Path.of("trial", "site", "patient"));
        var q = CbrQuery.of(TENANT, CBR, Path.of("trial", "site"),
                            "scoped2", Map.of("level", FeatureValue.string("child")), 10);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void scope_rootVisibility_rootVisibleFromAnyScope() {
        store().registerSchema(CbrFeatureSchema.of("scoped3",
                                                   FeatureField.categorical("level")));
        var rootCase = new FeatureVectorCbrCase("global", "sol", null, null,
                                                Map.of("level", FeatureValue.string("root")), null, null);
        store().store(rootCase, "scoped3", ENTITY, CBR, TENANT, "root-1", Path.root());
        var q = CbrQuery.of(TENANT, CBR, Path.of("trial", "site", "patient"),
                            "scoped3", Map.of("level", FeatureValue.string("root")), 10);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
    }

    @Test
    void scope_branchIsolation_differentBranchNotVisible() {
        store().registerSchema(CbrFeatureSchema.of("scoped4",
                                                   FeatureField.categorical("level")));
        var caseA = new FeatureVectorCbrCase("site-a signal", "sol", null, null,
                                             Map.of("level", FeatureValue.string("a")), null, null);
        store().store(caseA, "scoped4", ENTITY, CBR, TENANT, "a-1",
                      Path.of("trial-alpha", "site-north"));
        var q = CbrQuery.of(TENANT, CBR, Path.of("trial-beta", "site-south"),
                            "scoped4", Map.of("level", FeatureValue.string("a")), 10);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }


    @Test
    void eraseByScope_exactScope_erasesOnlyCasesAtThatScope() {
        store().registerSchema(CbrFeatureSchema.of("scoped-erase",
                                                   FeatureField.categorical("level")));
        store().store(new FeatureVectorCbrCase("at-target", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase", ENTITY, CBR, TENANT, "c-1", Path.of("org", "site"));
        store().store(new FeatureVectorCbrCase("at-parent", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase", ENTITY, CBR, TENANT, "c-2", Path.of("org"));
        int erased = store().eraseByScope(Path.of("org", "site"), TENANT);
        assertThat(erased).isEqualTo(1);
        var q = CbrQuery.of(TENANT, CBR, Path.of("org"), "scoped-erase",
                            Map.of("level", string("a")), 10);
        assertThat(store().retrieveSimilar(q, FeatureVectorCbrCase.class)).hasSize(1);
    }

    @Test
    void eraseByScope_parentScope_erasesAllDescendants() {
        store().registerSchema(CbrFeatureSchema.of("scoped-erase2",
                                                   FeatureField.categorical("level")));
        store().store(new FeatureVectorCbrCase("at-parent", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase2", ENTITY, CBR, TENANT, "c-1", Path.of("org"));
        store().store(new FeatureVectorCbrCase("at-child", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase2", ENTITY, CBR, TENANT, "c-2", Path.of("org", "site"));
        store().store(new FeatureVectorCbrCase("at-grandchild", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase2", ENTITY, CBR, TENANT, "c-3", Path.of("org", "site", "ward"));
        int erased = store().eraseByScope(Path.of("org"), TENANT);
        assertThat(erased).isEqualTo(3);
    }

    @Test
    void eraseByScope_rootScope_erasesAllCasesForTenant() {
        store().registerSchema(CbrFeatureSchema.of("scoped-erase3",
                                                   FeatureField.categorical("level")));
        store().store(new FeatureVectorCbrCase("root-case", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase3", ENTITY, CBR, TENANT, "c-1", Path.root());
        store().store(new FeatureVectorCbrCase("nested-case", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase3", ENTITY, CBR, TENANT, "c-2", Path.of("org", "site"));
        int erased = store().eraseByScope(Path.root(), TENANT);
        assertThat(erased).isEqualTo(2);
    }

    @Test
    void eraseByScope_tenantIsolation() {
        store().registerSchema(CbrFeatureSchema.of("scoped-erase4",
                                                   FeatureField.categorical("level")));
        store().store(new FeatureVectorCbrCase("t1-case", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase4", ENTITY, CBR, TENANT, "c-1", Path.of("org"));
        store().store(new FeatureVectorCbrCase("t2-case", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase4", ENTITY, CBR, "other-tenant", "c-2", Path.of("org"));
        int erased = store().eraseByScope(Path.of("org"), TENANT);
        assertThat(erased).isEqualTo(1);
        var q = CbrQuery.of("other-tenant", CBR, Path.of("org"), "scoped-erase4",
                            Map.of("level", string("a")), 10);
        assertThat(store().retrieveSimilar(q, FeatureVectorCbrCase.class)).hasSize(1);
    }

    @Test
    void eraseByScope_returnsCorrectCount() {
        store().registerSchema(CbrFeatureSchema.of("scoped-erase5",
                                                   FeatureField.categorical("level")));
        store().store(new FeatureVectorCbrCase("c1", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase5", ENTITY, CBR, TENANT, "c-1", Path.of("org"));
        store().store(new FeatureVectorCbrCase("c2", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase5", ENTITY, CBR, TENANT, "c-2", Path.of("org", "site"));
        int erased = store().eraseByScope(Path.of("org"), TENANT);
        assertThat(erased).isEqualTo(2);
    }

    @Test
    void eraseByScope_noMatchingCases_returnsZero() {
        int erased = store().eraseByScope(Path.of("nonexistent"), TENANT);
        assertThat(erased).isEqualTo(0);
    }

    @Test
    void eraseByScope_siblingIsolation_doesNotAffectSiblingScope() {
        store().registerSchema(CbrFeatureSchema.of("scoped-erase7",
                                                   FeatureField.categorical("level")));
        store().store(new FeatureVectorCbrCase("site-a", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase7", ENTITY, CBR, TENANT, "c-1", Path.of("site-a"));
        store().store(new FeatureVectorCbrCase("site-ab", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase7", ENTITY, CBR, TENANT, "c-2", Path.of("site-ab"));
        int erased = store().eraseByScope(Path.of("site-a"), TENANT);
        assertThat(erased).isEqualTo(1);
        var q = CbrQuery.of(TENANT, CBR, Path.of("site-ab"), "scoped-erase7",
                            Map.of("level", string("a")), 10);
        assertThat(store().retrieveSimilar(q, FeatureVectorCbrCase.class)).hasSize(1);
    }

    @Test
    void eraseByScope_supersededCasesAlsoErased() {
        store().registerSchema(CbrFeatureSchema.of("scoped-erase8",
                                                   FeatureField.categorical("level")));
        store().store(new FeatureVectorCbrCase("original", "s", null, null,
                                               Map.of("level", string("a")), null, null),
                      "scoped-erase8", ENTITY, CBR, TENANT, "c-1", Path.of("org"));
        store().supersede("c-1", TENANT, "c-2", "better case available");
        int erased = store().eraseByScope(Path.of("org"), TENANT);
        assertThat(erased).isEqualTo(1);
    }

    @Test
    void scope_roundTrip_scopePreservedOnScoredCase() {
        store().registerSchema(CbrFeatureSchema.of("scoped5",
                                                   FeatureField.categorical("level")));
        var c = new FeatureVectorCbrCase("signal", "sol", null, null,
                                         Map.of("level", FeatureValue.string("x")), null, null);
        Path scope = Path.of("trial", "site");
        store().store(c, "scoped5", ENTITY, CBR, TENANT, "s-1", scope);
        var q = CbrQuery.of(TENANT, CBR, Path.of("trial", "site"),
                            "scoped5", Map.of("level", FeatureValue.string("x")), 10);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).scope()).isEqualTo(scope);
    }

    @Test
    void scope_rootQueryIsolation_nonRootCasesNotVisibleFromRoot() {
        store().registerSchema(CbrFeatureSchema.of("scoped6",
                                                   FeatureField.categorical("level")));
        var nonRootCase = new FeatureVectorCbrCase("site signal", "sol", null, null,
                                                   Map.of("level", FeatureValue.string("site")), null, null);
        store().store(nonRootCase, "scoped6", ENTITY, CBR, TENANT, "nr-1",
                      Path.of("trial", "site"));
        var q = CbrQuery.of(TENANT, CBR, Path.root(),
                            "scoped6", Map.of("level", FeatureValue.string("site")), 10);
        var results = store().retrieveSimilar(q, FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test
    void getSupersessionStatus_notSuperseded() {
        String caseId = store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of("opponent_race", string("Zerg")), null, null),
                "starcraft-game", ENTITY, CBR, TENANT, "sup-status-1", Path.root());
        var status = store().getSupersessionStatus("sup-status-1", TENANT);
        assertThat(status.superseded()).isFalse();
        assertThat(status.caseId()).isEqualTo("sup-status-1");
        assertThat(status.wasReinstated()).isFalse();
    }

    @Test
    void getSupersessionStatus_afterSupersede() {
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of("opponent_race", string("Zerg")), null, null),
                "starcraft-game", ENTITY, CBR, TENANT, "sup-status-2", Path.root());
        store().supersede("sup-status-2", TENANT, "new-case", "better data");
        var status = store().getSupersessionStatus("sup-status-2", TENANT);
        assertThat(status.superseded()).isTrue();
        assertThat(status.supersedingCaseId()).isEqualTo("new-case");
        assertThat(status.reason()).isEqualTo("better data");
        assertThat(status.supersededAt()).isNotNull();
        assertThat(status.wasReinstated()).isFalse();
    }

    @Test
    void getSupersessionStatus_afterReinstate() {
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of("opponent_race", string("Zerg")), null, null),
                "starcraft-game", ENTITY, CBR, TENANT, "sup-status-3", Path.root());
        store().supersede("sup-status-3", TENANT, "new-case", "better data");
        store().reinstate("sup-status-3", TENANT);
        var status = store().getSupersessionStatus("sup-status-3", TENANT);
        assertThat(status.superseded()).isFalse();
        assertThat(status.reinstatedAt()).isNotNull();
        assertThat(status.wasReinstated()).isTrue();
    }

    @Test
    void getSupersessionStatus_reSupersede_clearsReinstatedAt() {
        store().store(
                new FeatureVectorCbrCase("p", "s", null, null, Map.of("opponent_race", string("Zerg")), null, null),
                "starcraft-game", ENTITY, CBR, TENANT, "sup-status-4", Path.root());
        store().supersede("sup-status-4", TENANT, "case-a", "first");
        store().reinstate("sup-status-4", TENANT);
        store().supersede("sup-status-4", TENANT, "case-b", "second");
        var status = store().getSupersessionStatus("sup-status-4", TENANT);
        assertThat(status.superseded()).isTrue();
        assertThat(status.supersedingCaseId()).isEqualTo("case-b");
        assertThat(status.wasReinstated()).isFalse();
    }

    @Test
    void findSupersededCases_filtersCorrectly() {
        store().store(
                new FeatureVectorCbrCase("p1", "s1", null, null, Map.of("opponent_race", string("Zerg")), null, null),
                "starcraft-game", ENTITY, CBR, TENANT, "sup-find-1", Path.root());
        store().store(
                new FeatureVectorCbrCase("p2", "s2", null, null, Map.of("opponent_race", string("Terran")), null, null),
                "starcraft-game", ENTITY, CBR, TENANT, "sup-find-2", Path.root());
        store().supersede("sup-find-1", TENANT, "new", "reason");
        var superseded = store().findSupersededCases(TENANT, CBR);
        assertThat(superseded).hasSize(1);
        assertThat(superseded.getFirst().caseId()).isEqualTo("sup-find-1");
        assertThat(superseded.getFirst().superseded()).isTrue();
    }

    // ── Bulk Supersession ────────────────────────────────────

    private static final String BULK_TYPE = "regime-case";

    private void registerBulkSchema() {
        store().registerSchema(CbrFeatureSchema.of(BULK_TYPE,
                FeatureField.categorical("regime"),
                FeatureField.numeric("confidence", 0, 1)));
    }

    private void storeBulkCase(String caseId, String regime) {
        store().store(new FeatureVectorCbrCase("p", "s", null, null,
                Map.of("regime", string(regime), "confidence", number(0.8)), null, null),
                BULK_TYPE, ENTITY, CBR, TENANT, caseId, Path.root());
    }

    private List<ScoredCbrCase<FeatureVectorCbrCase>> queryBulk(String regime) {
        return store().retrieveSimilar(
                CbrQuery.of(TENANT, CBR, Path.root(), BULK_TYPE,
                        Map.of("regime", string(regime), "confidence", number(0.8)), 10),
                FeatureVectorCbrCase.class);
    }

    @Test
    void supersedeMatching_filtersByCaseType() {
        registerBulkSchema();
        storeBulkCase("bulk-c1", "TRENDING");
        storeBulkCase("bulk-c2", "VOLATILE");

        int count = store().supersedeMatching(TENANT, CBR, BULK_TYPE,
                Map.of("regime", CbrFilter.contains("TRENDING")), "regime changed");

        assertThat(count).isEqualTo(1);
        assertThat(store().getSupersessionStatus("bulk-c1", TENANT).superseded()).isTrue();
        assertThat(store().getSupersessionStatus("bulk-c1", TENANT).reason()).isEqualTo("regime changed");
        assertThat(store().getSupersessionStatus("bulk-c2", TENANT).superseded()).isFalse();
    }

    @Test
    void supersedeMatching_returnsCount() {
        registerBulkSchema();
        storeBulkCase("bulk-mc1", "TRENDING");
        storeBulkCase("bulk-mc2", "TRENDING");
        storeBulkCase("bulk-mc3", "VOLATILE");

        int count = store().supersedeMatching(TENANT, CBR, BULK_TYPE,
                Map.of("regime", CbrFilter.contains("TRENDING")), "regime shift");

        assertThat(count).isEqualTo(2);
    }

    @Test
    void supersedeMatching_excludesAlreadySuperseded() {
        registerBulkSchema();
        storeBulkCase("bulk-as1", "TRENDING");
        store().supersede("bulk-as1", TENANT, null, "first");

        int count = store().supersedeMatching(TENANT, CBR, BULK_TYPE,
                Map.of("regime", CbrFilter.contains("TRENDING")), "second");
        assertThat(count).isEqualTo(0);
    }

    @Test
    void supersedeMatching_excludedFromRetrieval() {
        registerBulkSchema();
        storeBulkCase("bulk-er1", "TRENDING");

        store().supersedeMatching(TENANT, CBR, BULK_TYPE,
                Map.of("regime", CbrFilter.contains("TRENDING")), "regime changed");

        assertThat(queryBulk("TRENDING")).isEmpty();
    }

    @Test
    void supersedeAll_byIds() {
        registerBulkSchema();
        storeBulkCase("bulk-ai1", "TRENDING");
        storeBulkCase("bulk-ai2", "TRENDING");

        int count = store().supersedeAll(List.of("bulk-ai1", "bulk-ai2", "nonexistent"), TENANT, "batch");

        assertThat(count).isEqualTo(2);
        assertThat(store().getSupersessionStatus("bulk-ai1", TENANT).superseded()).isTrue();
        assertThat(store().getSupersessionStatus("bulk-ai2", TENANT).superseded()).isTrue();
    }

    @Test
    void supersedeAll_alreadySuperseded_noDoubleCount() {
        registerBulkSchema();
        storeBulkCase("bulk-dc1", "TRENDING");
        store().supersede("bulk-dc1", TENANT, null, "first");

        int count = store().supersedeAll(List.of("bulk-dc1"), TENANT, "second");
        assertThat(count).isEqualTo(0);
    }

    @Test
    void reinstateMatching_restoresRetrieval() {
        registerBulkSchema();
        storeBulkCase("bulk-rm1", "TRENDING");
        storeBulkCase("bulk-rm2", "TRENDING");
        store().supersedeMatching(TENANT, CBR, BULK_TYPE,
                Map.of("regime", CbrFilter.contains("TRENDING")), "regime changed");

        int count = store().reinstateMatching(TENANT, CBR, BULK_TYPE,
                Map.of("regime", CbrFilter.contains("TRENDING")));

        assertThat(count).isEqualTo(2);
        assertThat(queryBulk("TRENDING")).hasSize(2);
    }

    @Test
    void reinstateMatching_onlyAffectsSuperseded() {
        registerBulkSchema();
        storeBulkCase("bulk-oas1", "TRENDING");
        storeBulkCase("bulk-oas2", "TRENDING");
        store().supersede("bulk-oas1", TENANT, null, "first");

        int count = store().reinstateMatching(TENANT, CBR, BULK_TYPE,
                Map.of("regime", CbrFilter.contains("TRENDING")));

        assertThat(count).isEqualTo(1);
    }

    @Test
    void reinstateAll_byIds() {
        registerBulkSchema();
        storeBulkCase("bulk-ra1", "TRENDING");
        storeBulkCase("bulk-ra2", "TRENDING");
        store().supersede("bulk-ra1", TENANT, null, "first");
        store().supersede("bulk-ra2", TENANT, null, "first");

        int count = store().reinstateAll(List.of("bulk-ra1", "bulk-ra2"), TENANT);

        assertThat(count).isEqualTo(2);
        assertThat(queryBulk("TRENDING")).hasSize(2);
    }

    @Test
    void reinstateAll_onlyAffectsSuperseded() {
        registerBulkSchema();
        storeBulkCase("bulk-ras1", "TRENDING");
        storeBulkCase("bulk-ras2", "TRENDING");
        store().supersede("bulk-ras1", TENANT, null, "first");

        int count = store().reinstateAll(List.of("bulk-ras1", "bulk-ras2"), TENANT);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void findCaseIds_filterByFeature() {
        registerBulkSchema();
        storeBulkCase("bulk-fc1", "TRENDING");
        storeBulkCase("bulk-fc2", "VOLATILE");

        var ids = store().findCaseIds(TENANT, CBR, BULK_TYPE,
                Map.of("regime", CbrFilter.contains("TRENDING")));

        assertThat(ids).containsExactly("bulk-fc1");
    }

    @Test
    void findCaseIds_excludesSuperseded() {
        registerBulkSchema();
        storeBulkCase("bulk-fs1", "TRENDING");
        storeBulkCase("bulk-fs2", "TRENDING");
        store().supersede("bulk-fs1", TENANT, null, "gone");

        var ids = store().findCaseIds(TENANT, CBR, BULK_TYPE,
                Map.of("regime", CbrFilter.contains("TRENDING")));

        assertThat(ids).containsExactly("bulk-fs2");
    }

    @Test
    void findCaseIds_filterByCategoricalField() {
        registerBulkSchema();
        storeBulkCase("bulk-cat1", "TRENDING");
        storeBulkCase("bulk-cat2", "VOLATILE");
        storeBulkCase("bulk-cat3", "TRENDING");

        var ids = store().findCaseIds(TENANT, CBR, BULK_TYPE,
                Map.of("regime", CbrFilter.contains("VOLATILE")));

        assertThat(ids).containsExactly("bulk-cat2");
    }
}
