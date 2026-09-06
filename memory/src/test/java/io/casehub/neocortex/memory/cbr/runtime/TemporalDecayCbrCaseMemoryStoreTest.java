package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.TemporalDecay;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TemporalDecayCbrCaseMemoryStoreTest {

    @Test void nullTemporalDecay_passThrough() {
        var c = testCase("p1");
        Instant oneHourAgo = Instant.now().minus(Duration.ofHours(1));
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(c, "c1", 0.9, false, Map.of(), oneHourAgo, io.casehub.platform.api.path.Path.root(), null)));
        var decorator = new TemporalDecayCbrCaseMemoryStore(delegate);
        var query = testQuery();

        var results = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isEqualTo(0.9);
    }

    @Test void halfLife_appliesExponentialFactor() {
        var c = testCase("p1");
        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(Duration.ofHours(1));
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(c, "c1", 0.8, false, Map.of(), oneHourAgo, io.casehub.platform.api.path.Path.root(), null)));
        var decorator = new TemporalDecayCbrCaseMemoryStore(delegate);
        var query = testQuery().withTemporalDecay(new TemporalDecay.HalfLife(Duration.ofHours(1)));

        var results = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isCloseTo(0.4, within(0.05));
    }

    @Test void linear_rampToZero() {
        var c = testCase("p1");
        Instant now = Instant.now();
        Instant thirtyDaysAgo = now.minus(Duration.ofDays(30));
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(c, "c1", 0.8, false, Map.of(), thirtyDaysAgo, io.casehub.platform.api.path.Path.root(), null)));
        var decorator = new TemporalDecayCbrCaseMemoryStore(delegate);
        var query = testQuery()
                .withMinSimilarity(0.01)
                .withTemporalDecay(new TemporalDecay.Linear(Duration.ofDays(30)));

        var results = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).isEmpty();
    }

    @Test void step_thresholdBehavior() {
        var c1 = testCase("recent");
        var c2 = testCase("old");
        Instant now = Instant.now();
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(c1, "c1", 0.8, false, Map.of(), now.minus(Duration.ofDays(3)), io.casehub.platform.api.path.Path.root(), null),
                new ScoredCbrCase<>(c2, "c2", 0.8, false, Map.of(), now.minus(Duration.ofDays(10)), io.casehub.platform.api.path.Path.root(), null)));
        var decorator = new TemporalDecayCbrCaseMemoryStore(delegate);
        var query = testQuery().withTemporalDecay(new TemporalDecay.Step(Duration.ofDays(7), 0.3));

        var results = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).score()).isEqualTo(0.8);
        assertThat(results.get(1).score()).isCloseTo(0.24, within(0.01));
    }

    @Test void refiltersMinSimilarity() {
        var c1 = testCase("recent");
        var c2 = testCase("old");
        Instant now = Instant.now();
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(c1, "c1", 0.6, false, Map.of(), now.minus(Duration.ofMinutes(5)), io.casehub.platform.api.path.Path.root(), null),
                new ScoredCbrCase<>(c2, "c2", 0.6, false, Map.of(), now.minus(Duration.ofDays(60)), io.casehub.platform.api.path.Path.root(), null)));
        var decorator = new TemporalDecayCbrCaseMemoryStore(delegate);
        var query = CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 10)
                .withMinSimilarity(0.5)
                .withTemporalDecay(new TemporalDecay.HalfLife(Duration.ofDays(7)));

        var results = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("recent");
    }

    @Test void resorts() {
        var recent = testCase("recent");
        var old = testCase("old");
        Instant now = Instant.now();
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(old, "c1", 0.9, false, Map.of(), now.minus(Duration.ofDays(60)), io.casehub.platform.api.path.Path.root(), null),
                new ScoredCbrCase<>(recent, "c2", 0.7, false, Map.of(), now.minus(Duration.ofMinutes(5)), io.casehub.platform.api.path.Path.root(), null)));
        var decorator = new TemporalDecayCbrCaseMemoryStore(delegate);
        var query = testQuery().withTemporalDecay(new TemporalDecay.HalfLife(Duration.ofDays(7)));

        var results = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results.getFirst().cbrCase().problem()).isEqualTo("recent");
    }

    @Test void nullStoredAt_factorIsOne() {
        var c = testCase("p1");
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(c, "c1", 0.8, false, Map.of(), null, io.casehub.platform.api.path.Path.root(), null)));
        var decorator = new TemporalDecayCbrCaseMemoryStore(delegate);
        var query = testQuery().withTemporalDecay(new TemporalDecay.HalfLife(Duration.ofHours(1)));

        var results = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isEqualTo(0.8);
    }

    private FeatureVectorCbrCase testCase(String problem) {
        return new FeatureVectorCbrCase(problem, "sol", null, null, Map.of(), null, null);
    }

    private CbrQuery testQuery() {
        return CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 10);
    }

    @SuppressWarnings("unchecked")
    private CbrCaseMemoryStore stubDelegate(List<ScoredCbrCase<FeatureVectorCbrCase>> results) {
        return new CbrCaseMemoryStore() {
            @Override public void registerSchema(CbrFeatureSchema schema) {}
            @Override public String store(CbrCase c, String t, String e, MemoryDomain d, String tid, String cid, io.casehub.platform.api.path.Path scope) { return "id"; }
            @Override public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery q, Class<C> cl) {
                return (List<ScoredCbrCase<C>>) (List<?>) results;
            }
            @Override public Integer erase(EraseRequest r) { return 0; }
            @Override public Integer eraseEntity(String e, String t) { return 0; }
            @Override public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String t) { return 0; }
            @Override public void recordOutcome(String c, String t, CbrOutcome o) {}
            @Override public Integer purge(CbrRetentionPolicy p) { return 0; }
            @Override public boolean supersede(String c, String t, String s, String r) { return false; }
            @Override public boolean reinstate(String c, String t) { return false; }

        @Override public io.casehub.neocortex.memory.cbr.SupersessionStatus getSupersessionStatus(String caseId, String tenantId) { return io.casehub.neocortex.memory.cbr.SupersessionStatus.NOT_SUPERSEDED; }
        @Override public java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) { return java.util.List.of(); }
        @Override public java.util.List<String> findCaseIds(String t, io.casehub.neocortex.memory.MemoryDomain d, String ct, java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> f) { return java.util.List.of(); }
        @Override public int supersedeMatching(String t, io.casehub.neocortex.memory.MemoryDomain d, String ct, java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> f, String r) { return 0; }
        @Override public int supersedeAll(java.util.Collection<String> ids, String t, String r) { return 0; }
        @Override public int reinstateMatching(String t, io.casehub.neocortex.memory.MemoryDomain d, String ct, java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> f) { return 0; }
        @Override public int reinstateAll(java.util.Collection<String> ids, String t) { return 0; }

        };
    }
}
