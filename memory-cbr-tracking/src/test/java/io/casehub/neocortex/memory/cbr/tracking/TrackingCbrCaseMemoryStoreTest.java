package io.casehub.neocortex.memory.cbr.tracking;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetrievalRecorded;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTrace;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.testing.InMemoryCbrRetrievalTracker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingCbrCaseMemoryStoreTest {

    @Test void recordsTraceAndFiresEvent() {
        var tracker = new InMemoryCbrRetrievalTracker();
        var eventRef = new AtomicReference<CbrRetrievalRecorded>();
        var c = new FeatureVectorCbrCase("p", "s", null, Confidence.unknown(0.9), Map.of(), null, null);
        var results = List.<ScoredCbrCase<FeatureVectorCbrCase>>of(
                new ScoredCbrCase<>(c, "c1", 0.85));
        var delegate = stubDelegate(results);
        var decorator = new TrackingCbrCaseMemoryStore(delegate, tracker, eventRef::set);

        var query = CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        var returned = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(returned).isEqualTo(results);
        assertThat(eventRef.get()).isNotNull();
        assertThat(eventRef.get().traceId()).isNotBlank();
        assertThat(eventRef.get().results()).hasSize(1);
        assertThat(eventRef.get().results().getFirst().caseId()).isEqualTo("c1");
    }

    @Test void trackerFailure_nonFatal() {
        CbrRetrievalTracker failingTracker = new CbrRetrievalTracker() {
            @Override public String record(CbrQuery q, List<ScoredCbrCase<?>> r) {
                throw new RuntimeException("boom");
            }
            @Override public List<CbrRetrievalTrace> findTraces(String ct, String t, MemoryDomain d, Instant s, Instant u) { return List.of(); }
            @Override public int purgeOlderThan(Instant cutoff) { return 0; }
        };
        var c = new FeatureVectorCbrCase("p", "s", null, Confidence.unknown(0.9), Map.of(), null, null);
        var results = List.<ScoredCbrCase<FeatureVectorCbrCase>>of(
                new ScoredCbrCase<>(c, "c1", 0.85));
        var delegate = stubDelegate(results);
        var decorator = new TrackingCbrCaseMemoryStore(delegate, failingTracker, e -> {});

        var query = CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        var returned = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(returned).isEqualTo(results);
    }

    @Test void resultsUnchanged() {
        var tracker = new InMemoryCbrRetrievalTracker();
        var c = new FeatureVectorCbrCase("p", "s", null, Confidence.unknown(0.9), Map.of(), null, null);
        var results = List.<ScoredCbrCase<FeatureVectorCbrCase>>of(
                new ScoredCbrCase<>(c, "c1", 0.85, true, Map.of("f", 0.9), null, io.casehub.platform.api.path.Path.root(), null));
        var delegate = stubDelegate(results);
        var decorator = new TrackingCbrCaseMemoryStore(delegate, tracker, e -> {});

        var query = CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        var returned = decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);
        assertThat(returned.getFirst().score()).isEqualTo(0.85);
        assertThat(returned.getFirst().reranked()).isTrue();
        assertThat(returned.getFirst().caseId()).isEqualTo("c1");
    }

    @Test
    void trustTrajectory_convertedToLabel() {
        var tracker  = new InMemoryCbrRetrievalTracker();
        var eventRef = new AtomicReference<CbrRetrievalRecorded>();
        var c        = new FeatureVectorCbrCase("p", "s", null, Confidence.unknown(0.9), Map.of(), 0.8, "agent-1");
        var scored = new ScoredCbrCase<>(c, "c1", 0.85, false, Map.of(), null,
                                         io.casehub.platform.api.path.Path.root(), -0.2);
        var delegate  = stubDelegate(List.of(scored));
        var decorator = new TrackingCbrCaseMemoryStore(delegate, tracker, eventRef::set);

        var query = CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(eventRef.get().results()).hasSize(1);
        var traced = eventRef.get().results().getFirst();
        assertThat(traced.trustScore()).isEqualTo(0.8);
        assertThat(traced.producerAgentId()).isEqualTo("agent-1");
        assertThat(traced.trustTrajectory()).isEqualTo("declining");
    }

    @Test
    void trustTrajectory_null_staysNull() {
        var tracker  = new InMemoryCbrRetrievalTracker();
        var eventRef = new AtomicReference<CbrRetrievalRecorded>();
        var c        = new FeatureVectorCbrCase("p", "s", null, Confidence.unknown(0.9), Map.of(), 0.8, "agent-1");
        var scored = new ScoredCbrCase<>(c, "c1", 0.85, false, Map.of(), null,
                                         io.casehub.platform.api.path.Path.root(), null);
        var delegate  = stubDelegate(List.of(scored));
        var decorator = new TrackingCbrCaseMemoryStore(delegate, tracker, eventRef::set);

        var query = CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(eventRef.get().results().getFirst().trustTrajectory()).isNull();
    }

    @Test
    void trustTrajectory_improving() {
        var tracker  = new InMemoryCbrRetrievalTracker();
        var eventRef = new AtomicReference<CbrRetrievalRecorded>();
        var c        = new FeatureVectorCbrCase("p", "s", null, Confidence.unknown(0.9), Map.of(), 0.5, "agent-1");
        var scored = new ScoredCbrCase<>(c, "c1", 0.85, false, Map.of(), null,
                                         io.casehub.platform.api.path.Path.root(), 0.3);
        var delegate  = stubDelegate(List.of(scored));
        var decorator = new TrackingCbrCaseMemoryStore(delegate, tracker, eventRef::set);

        var query = CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(eventRef.get().results().getFirst().trustTrajectory()).isEqualTo("improving");
    }

    @Test
    void trustTrajectory_stable() {
        var tracker  = new InMemoryCbrRetrievalTracker();
        var eventRef = new AtomicReference<CbrRetrievalRecorded>();
        var c        = new FeatureVectorCbrCase("p", "s", null, Confidence.unknown(0.9), Map.of(), 0.8, "agent-1");
        var scored = new ScoredCbrCase<>(c, "c1", 0.85, false, Map.of(), null,
                                         io.casehub.platform.api.path.Path.root(), 0.0);
        var delegate  = stubDelegate(List.of(scored));
        var decorator = new TrackingCbrCaseMemoryStore(delegate, tracker, eventRef::set);

        var query = CbrQuery.of("t1", new MemoryDomain("cbr"), io.casehub.platform.api.path.Path.root(), "default", Map.of(), 5);
        decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        assertThat(eventRef.get().results().getFirst().trustTrajectory()).isEqualTo("stable");
    }


    @SuppressWarnings("unchecked")
    private CbrCaseMemoryStore stubDelegate(List<? extends ScoredCbrCase<?>> results) {
        return new CbrCaseMemoryStore() {
            @Override public void registerSchema(CbrFeatureSchema s) {}
            @Override public String store(CbrCase c, String t, String e, MemoryDomain d, String tid, String cid, io.casehub.platform.api.path.Path scope) { return "id"; }
            @Override public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery q, Class<C> cl) {
                return (List<ScoredCbrCase<C>>) (List<?>) results;
            }
            @Override public Integer erase(EraseRequest r) { return 0; }
            @Override public Integer eraseEntity(String e, String t) { return 0; }
            @Override public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String t) { return 0; }
            @Override public void recordOutcome(String c, String t, CbrOutcome o) {}
            @Override public Integer purge(io.casehub.neocortex.memory.cbr.CbrRetentionPolicy p) { return 0; }
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
