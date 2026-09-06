package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.TrendSpec;
import io.casehub.neocortex.memory.cbr.TrendType;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.casehub.neocortex.memory.cbr.FeatureValue.number;
import static io.casehub.neocortex.memory.cbr.FeatureValue.string;
import static org.assertj.core.api.Assertions.assertThat;

class TrendEnrichmentCbrCaseMemoryStoreTest {

    private static final MemoryDomain CBR = new MemoryDomain("cbr");

    private static final CbrFeatureSchema SCHEMA = CbrFeatureSchema.of("clinical",
            FeatureField.categorical("drug"),
            FeatureField.timeSeries("vitals", "t",
                    null,
                    new TrendSpec(Set.of(TrendType.SLOPE, TrendType.DELTA), ChronoUnit.HOURS),
                    FeatureField.numeric("t", 0, 100),
                    FeatureField.numeric("hr", 40, 200)));

    @Test
    void registerSchema_expandsSchema() {
        var capturedSchema = new AtomicReference<CbrFeatureSchema>();
        var delegate = stubDelegate(capturedSchema, null, null);
        var decorator = new TrendEnrichmentCbrCaseMemoryStore(delegate);
        decorator.registerSchema(SCHEMA);
        var expanded = capturedSchema.get();
        assertThat(expanded.fields().stream().map(FeatureField::name).toList())
                .contains("vitals_slope_hr", "vitals_delta_hr");
    }

    @Test
    void store_enrichesCaseFeatures() {
        var capturedCase = new AtomicReference<CbrCase>();
        var delegate = stubDelegate(null, capturedCase, null);
        var decorator = new TrendEnrichmentCbrCaseMemoryStore(delegate);
        decorator.registerSchema(SCHEMA);

        var obs = List.of(
                Map.<String, FeatureValue>of("t", number(0), "hr", number(60)),
                Map.<String, FeatureValue>of("t", number(1), "hr", number(80)));
        var cbrCase = new FeatureVectorCbrCase("prob", "sol", null, null,
                                               Map.of("drug", string("aspirin"), "vitals", FeatureValue.structList(obs)), null, null);
        decorator.store(cbrCase, "clinical", "e1", CBR, "t1", null, io.casehub.platform.api.path.Path.root());

        var stored = capturedCase.get();
        assertThat(stored.features()).containsKey("vitals_slope_hr");
        assertThat(stored.features()).containsKey("vitals_delta_hr");
        assertThat(stored.features().get("vitals_slope_hr"))
                .isEqualTo(number(20.0));
        assertThat(stored.features().get("vitals_delta_hr"))
                .isEqualTo(number(20.0));
    }

    @Test
    void retrieveSimilar_enrichesQueryFeatures() {
        var capturedQuery = new AtomicReference<CbrQuery>();
        var delegate = stubDelegate(null, null, capturedQuery);
        var decorator = new TrendEnrichmentCbrCaseMemoryStore(delegate);
        decorator.registerSchema(SCHEMA);

        var obs = List.of(
                Map.<String, FeatureValue>of("t", number(0), "hr", number(60)),
                Map.<String, FeatureValue>of("t", number(2), "hr", number(100)));
        var query = CbrQuery.of("t1", CBR, io.casehub.platform.api.path.Path.root(), "clinical",
                Map.of("vitals", FeatureValue.structList(obs)), 10);
        decorator.retrieveSimilar(query, FeatureVectorCbrCase.class);

        var enrichedQuery = capturedQuery.get();
        assertThat(enrichedQuery.features()).containsKey("vitals_slope_hr");
        assertThat(enrichedQuery.features().get("vitals_slope_hr"))
                .isEqualTo(number(20.0));
    }

    @Test
    void noTrendSpec_passesThrough() {
        var noTrendSchema = CbrFeatureSchema.of("plain",
                FeatureField.categorical("drug"));
        var capturedCase = new AtomicReference<CbrCase>();
        var delegate = stubDelegate(null, capturedCase, null);
        var decorator = new TrendEnrichmentCbrCaseMemoryStore(delegate);
        decorator.registerSchema(noTrendSchema);

        var cbrCase = new FeatureVectorCbrCase("prob", "sol", null, null,
                                               Map.of("drug", string("aspirin")), null, null);
        decorator.store(cbrCase, "plain", "e1", CBR, "t1", null, io.casehub.platform.api.path.Path.root());

        assertThat(capturedCase.get().features()).doesNotContainKey("vitals_slope_hr");
        assertThat(capturedCase.get().features()).containsKey("drug");
    }

    @Test
    void unregisteredCaseType_passesThrough() {
        var capturedCase = new AtomicReference<CbrCase>();
        var delegate = stubDelegate(null, capturedCase, null);
        var decorator = new TrendEnrichmentCbrCaseMemoryStore(delegate);

        var cbrCase = new FeatureVectorCbrCase("prob", "sol", null, null,
                                               Map.of("drug", string("aspirin")), null, null);
        decorator.store(cbrCase, "unknown", "e1", CBR, "t1", null, io.casehub.platform.api.path.Path.root());

        assertThat(capturedCase.get()).isSameAs(cbrCase);
    }

    @SuppressWarnings("unchecked")
    private CbrCaseMemoryStore stubDelegate(AtomicReference<CbrFeatureSchema> capturedSchema,
                                            AtomicReference<CbrCase> capturedCase,
                                            AtomicReference<CbrQuery> capturedQuery) {
        return new CbrCaseMemoryStore() {
            @Override
            public void registerSchema(CbrFeatureSchema schema) {
                if (capturedSchema != null) capturedSchema.set(schema);
            }

            @Override
            public String store(CbrCase c, String t, String e, MemoryDomain d, String tid, String cid, io.casehub.platform.api.path.Path scope) {
                if (capturedCase != null) capturedCase.set(c);
                return "id";
            }

            @Override
            public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery q, Class<C> cl) {
                if (capturedQuery != null) capturedQuery.set(q);
                return List.of();
            }

            @Override
            public Integer erase(EraseRequest request) { return 0; }

            @Override
            public Integer eraseEntity(String entityId, String tenantId) { return 0; }
            @Override public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) { return 0; }

            @Override
            public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {}

            @Override
            public Integer purge(CbrRetentionPolicy policy) { return 0; }

            @Override
            public void supersede(String caseId, String tenantId, String supersedingCaseId, String reason) {}

            @Override
            public void reinstate(String caseId, String tenantId) {}

        @Override public io.casehub.neocortex.memory.cbr.SupersessionStatus getSupersessionStatus(String caseId, String tenantId) { return io.casehub.neocortex.memory.cbr.SupersessionStatus.NOT_SUPERSEDED; }
        @Override public java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) { return java.util.List.of(); }

        };
    }
}
