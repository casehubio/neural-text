package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class TrustWeightedCbrCaseMemoryStoreTest {

    private final DefaultTrustWeightingFunction fn = new DefaultTrustWeightingFunction(0.3, 0.5);

    @Test void nullTrustScore_passesThrough() {
        var c = testCase("p", null, null);
        var delegate = stubDelegate(List.of(new ScoredCbrCase<>(c, "c1", 0.9)));
        var decorator = new TrustWeightedCbrCaseMemoryStore(delegate, fn, (AgentTrustProvider) null);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(results.getFirst().score()).isCloseTo(0.9, offset(1e-9));
    }

    @Test void mixedNullAndNonNull_onlyWeightsNonNull() {
        var trusted = testCase("trusted", 0.9, "agent-1");
        var unknown = testCase("unknown", null, null);
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(trusted, "c1", 0.8),
                new ScoredCbrCase<>(unknown, "c2", 0.8)));
        var decorator = new TrustWeightedCbrCaseMemoryStore(delegate, fn, (AgentTrustProvider) null);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).score()).isCloseTo(0.8, offset(1e-9));
        assertThat(results.get(1).score()).isLessThan(0.8);
    }

    @Test void resortsAfterWeighting() {
        var highTrust = testCase("high", 1.0, "a1");
        var lowTrust = testCase("low", 0.1, "a2");
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(lowTrust, "c1", 0.9),
                new ScoredCbrCase<>(highTrust, "c2", 0.85)));
        var decorator = new TrustWeightedCbrCaseMemoryStore(delegate, fn, (AgentTrustProvider) null);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(results.get(0).score()).isGreaterThanOrEqualTo(results.get(1).score());
    }

    @Test void emptyResults_passesThrough() {
        var delegate = stubDelegate(List.of());
        var decorator = new TrustWeightedCbrCaseMemoryStore(delegate, fn, (AgentTrustProvider) null);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(results).isEmpty();
    }

    @Test void trajectoryCache_singleCallPerAgent() {
        var c1 = testCase("p1", 0.8, "same-agent");
        var c2 = testCase("p2", 0.7, "same-agent");
        var callCount = new AtomicInteger();
        AgentTrustProvider provider = agentId -> {
            callCount.incrementAndGet();
            return OptionalDouble.of(0.9);
        };
        var delegate = stubDelegate(List.of(
                new ScoredCbrCase<>(c1, "c1", 0.9),
                new ScoredCbrCase<>(c2, "c2", 0.8)));
        var decorator = new TrustWeightedCbrCaseMemoryStore(delegate, fn, provider);
        decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test void noAgentTrustProvider_authorityOnly() {
        var c = testCase("p", 0.8, "agent-1");
        var delegate = stubDelegate(List.of(new ScoredCbrCase<>(c, "c1", 0.9)));
        var decorator = new TrustWeightedCbrCaseMemoryStore(delegate, fn, (AgentTrustProvider) null);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        double expected = 0.9 * (1.0 - 0.3 + 0.3 * 0.8);
        assertThat(results.getFirst().score()).isCloseTo(expected, offset(1e-9));
        assertThat(results.getFirst().trustTrajectory()).isNull();
    }

    @Test void trustTrajectory_storedOnScoredCbrCase() {
        var c = testCase("p", 0.8, "agent-1");
        AgentTrustProvider provider = agentId -> OptionalDouble.of(0.6);
        var delegate = stubDelegate(List.of(new ScoredCbrCase<>(c, "c1", 0.9)));
        var decorator = new TrustWeightedCbrCaseMemoryStore(delegate, fn, provider);
        var results = decorator.retrieveSimilar(testQuery(), FeatureVectorCbrCase.class);
        assertThat(results.getFirst().trustTrajectory()).isCloseTo(-0.2, offset(1e-9));
    }

    private FeatureVectorCbrCase testCase(String problem, Double trustScore, String agentId) {
        return new FeatureVectorCbrCase(problem, "sol", null, null, Map.of(), trustScore, agentId);
    }

    private CbrQuery testQuery() {
        return CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 10);
    }

    @SuppressWarnings("unchecked")
    private CbrCaseMemoryStore stubDelegate(List<ScoredCbrCase<FeatureVectorCbrCase>> results) {
        return new CbrCaseMemoryStore() {
            @Override public void registerSchema(CbrFeatureSchema s) {}
            @Override public String store(CbrCase c, String t, String e, MemoryDomain d, String tid, String cid, io.casehub.platform.api.path.Path scope) { return "id"; }
            @Override public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery q, Class<C> cl) { return (List<ScoredCbrCase<C>>) (List<?>) results; }
            @Override public Integer erase(EraseRequest r) { return 0; }
            @Override public Integer eraseEntity(String e, String t) { return 0; }
            @Override public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String t) { return 0; }
            @Override public void recordOutcome(String c, String t, CbrOutcome o) {}
            @Override public Integer purge(CbrRetentionPolicy p) { return 0; }
            @Override public boolean supersede(String c, String t, String s, String r) { return false; }
            @Override public boolean reinstate(String c, String t) { return false; }
            @Override public SupersessionStatus getSupersessionStatus(String caseId, String tenantId) { return SupersessionStatus.NOT_SUPERSEDED; }
            @Override public java.util.List<SupersessionStatus> findSupersededCases(String tenantId, MemoryDomain domain) { return java.util.List.of(); }
            @Override public java.util.List<String> findCaseIds(String t, MemoryDomain d, String ct, java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> f) { return java.util.List.of(); }
            @Override public int supersedeMatching(String t, MemoryDomain d, String ct, java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> f, String r) { return 0; }
            @Override public int supersedeAll(java.util.Collection<String> ids, String t, String r) { return 0; }
            @Override public int reinstateMatching(String t, MemoryDomain d, String ct, java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> f) { return 0; }
            @Override public int reinstateAll(java.util.Collection<String> ids, String t) { return 0; }
        };
    }
}
