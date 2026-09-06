package io.casehub.neocortex.memory.cbr.inmem;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrCaseSummary;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrFeatureValidator;
import io.casehub.neocortex.memory.cbr.CbrFilter;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.CbrScanRequest;
import io.casehub.neocortex.memory.cbr.CbrScanResult;
import io.casehub.neocortex.memory.cbr.CbrSimilarityScorer;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.LbKeogh;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.SimilaritySpec;
import io.casehub.neocortex.memory.cbr.SupersessionStatus;
import io.casehub.neocortex.memory.cbr.WarpingConstraint;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Alternative
@Priority(2)
@ApplicationScoped
public class InMemoryCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private final Map<String, CbrFeatureSchema> schemas = new ConcurrentHashMap<>();
    private final List<StoredCase>              cases   = new CopyOnWriteArrayList<>();

    @Override
    public void registerSchema(CbrFeatureSchema schema) {
        schemas.put(schema.caseType(), schema);
    }

    public void clearCases() {
        cases.clear();
    }


    @Override
    public String store(CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
                        String tenantId, String caseId, io.casehub.platform.api.path.Path scope) {
        CbrFeatureSchema schema = schemas.get(caseType);
        if (schema != null) {
            CbrFeatureValidator.validateStoreFeatures(cbrCase.features(), schema);
        }
        java.util.Objects.requireNonNull(scope, "scope required");
        String id = UUID.randomUUID().toString();
        cases.add(new StoredCase(id, cbrCase, caseType, entityId, domain, tenantId, caseId, Instant.now(), null, null, null, null, scope, null));
        return id;
    }

    @Override
    public String store(CbrCase cbrCase, String caseType, io.casehub.neocortex.memory.Subject subject, MemoryDomain domain,
                        String tenantId, String caseId, io.casehub.platform.api.path.Path scope,
                        String principalId, java.util.Set<String> sharedWith) {
        return store(cbrCase, caseType, subject.id(), domain, tenantId, caseId, scope);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> caseClass) {
        if (query.retrievalMode() == RetrievalMode.SEMANTIC_ONLY) {
            return List.of();
        }
        if (query.retrievalMode() == RetrievalMode.HYBRID && query.problem() != null) {
            java.util.logging.Logger.getLogger(getClass().getName())
                                    .warning("HYBRID mode degraded to FEATURE_ONLY — no EmbeddingModel available");
        }

        CbrFeatureSchema schema = schemas.get(query.caseType());
        if (schema != null) {
            CbrFeatureValidator.validateQueryFeatures(query.features(), schema);
        }

        if (!query.filters().isEmpty()) {
            if (schema == null) {
                throw new IllegalStateException(
                        "Cannot apply structural filters: no schema registered for caseType '"
                        + query.caseType() + "'");
            }
            CbrFeatureValidator.validateFilters(query.filters(), schema);
        }

        List<FeatureField.TimeSeries> dtwBandFields = schema == null ? List.of()
                                                                     : schema.fields().stream()
                                                                             .filter(f -> f instanceof FeatureField.TimeSeries ts
                                                                                          && ts.similaritySpec() instanceof SimilaritySpec.DtwSpec ds
                                                                                          && ds.constraint() instanceof WarpingConstraint.SakoeChibaBand)
                                                                             .map(f -> (FeatureField.TimeSeries) f)
                                                                             .toList();

        List<ScoredCbrCase<C>> candidates = new ArrayList<>();
        for (StoredCase stored : cases) {
            if (!stored.tenantId().equals(query.tenantId())) {continue;}
            if (!stored.domain().equals(query.domain())) {continue;}
            if (!stored.caseType().equals(query.caseType())) {continue;}
            if (!isVisibleAtScope(stored.scope(), query.scope())) {continue;}
            if (query.notBefore() != null && stored.storedAt().isBefore(query.notBefore())) {continue;}
            if (stored.supersededAt() != null) {continue;}
            if (!caseClass.isInstance(stored.cbrCase())) {continue;}

            if (!matchesFilters(stored.cbrCase(), query.filters(), schema)) {continue;}

            double abandonCost = Double.POSITIVE_INFINITY;
            if (!dtwBandFields.isEmpty() && candidates.size() >= query.topK()) {
                double kthScore = candidates.get(query.topK() - 1).score();
                if (kthScore > 0) {
                    boolean pruned = false;
                    for (FeatureField.TimeSeries ts : dtwBandFields) {
                        int          windowSize = ((WarpingConstraint.SakoeChibaBand) ((SimilaritySpec.DtwSpec) ts.similaritySpec()).constraint()).windowSize();
                        FeatureValue queryTs    = query.features().get(ts.name());
                        FeatureValue caseTs     = stored.cbrCase().features().get(ts.name());
                        if (queryTs instanceof FeatureValue.StructListVal qObs
                            && caseTs instanceof FeatureValue.StructListVal cObs) {
                            int maxLen = Math.max(qObs.items().size(), cObs.items().size());
                            abandonCost = (1.0 / kthScore - 1.0) * maxLen;
                            LbKeogh.Envelope env = LbKeogh.computeEnvelope(cObs.items(), ts, windowSize);
                            if (LbKeogh.lowerBound(qObs.items(), env, ts) > abandonCost) {
                                pruned = true;
                                break;
                            }
                        }
                    }
                    if (pruned) {continue;}
                }
            }

            CbrSimilarityScorer.SimilarityBreakdown breakdown = CbrSimilarityScorer.scoreDetailed(
                    query.features(), stored.cbrCase().features(), query.weights(), schema, Map.of(),
                    abandonCost);

            double score = breakdown.score();
            if (score >= query.minSimilarity()) {
                candidates.add(new ScoredCbrCase<>((C) stored.cbrCase(), stored.caseId(),
                                                   score, false, breakdown.featureSimilarities(), stored.storedAt(), stored.scope(), null));
                candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
            }
        }

        List<ScoredCbrCase<C>> results = candidates.size() <= query.topK()
                                         ? candidates
                                         : candidates.subList(0, query.topK());
        return Collections.unmodifiableList(new ArrayList<>(results));
    }

    @Override
    public Integer erase(EraseRequest request) {
        int before = cases.size();
        cases.removeIf(sc ->
                               sc.subjectId().equals(request.subject().id())
                               && sc.domain().equals(request.domain())
                               && sc.tenantId().equals(request.tenantId())
                               && (request.caseId() == null || sc.caseId().equals(request.caseId())));
        return before - cases.size();
    }

    @Override
    public Integer eraseEntity(String entityId, String tenantId) {
        int before = cases.size();
        cases.removeIf(sc ->
                               sc.subjectId().equals(entityId) && sc.tenantId().equals(tenantId));
        return before - cases.size();
    }

    @Override
    public Integer eraseSubject(io.casehub.neocortex.memory.Subject subject, String tenantId) {
        return eraseEntity(subject.id(), tenantId);
    }


    @Override
    public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {
        java.util.Objects.requireNonNull(scope, "scope required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        int before = cases.size();
        cases.removeIf(sc -> sc.tenantId().equals(tenantId)
                             && (sc.scope().equals(scope) || scope.isAncestorOf(sc.scope())));
        return before - cases.size();
    }


    @Override
    public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        for (int i = 0; i < cases.size(); i++) {
            StoredCase stored = cases.get(i);
            if (caseId.equals(stored.caseId()) && tenantId.equals(stored.tenantId())) {
                if (stored.lastOutcomeAt() != null
                    && !outcome.observedAt().isAfter(stored.lastOutcomeAt())) {
                    return;
                }
                CbrFeatureSchema schema = schemas.get(stored.caseType());
                double lr = (schema != null && schema.learningRate() != null)
                            ? schema.learningRate() : CbrOutcome.DEFAULT_LEARNING_RATE;
                io.casehub.neocortex.cognitive.Confidence newConfidence = CbrOutcome.adjustConfidence(
                        stored.cbrCase().confidence(), outcome.successRate(), lr);
                CbrCase updated = stored.cbrCase().withOutcome(
                        outcome.result().name(), newConfidence);
                cases.set(i, new StoredCase(stored.id(), updated, stored.caseType(),
                                            stored.subjectId(), stored.domain(), stored.tenantId(),
                                            stored.caseId(), stored.storedAt(), outcome.observedAt(),
                                            stored.supersededAt(), stored.supersedingCaseId(), stored.supersessionReason(),
                                            stored.scope(), stored.reinstatedAt()));
                return;
            }
        }}

    @Override
    public Integer purge(CbrRetentionPolicy policy) {
        int before = cases.size();
        if (policy.maxAgeDays() != null) {
            Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(policy.maxAgeDays()));
            cases.removeIf(sc -> sc.tenantId().equals(policy.tenantId())
                                 && sc.domain().equals(policy.domain())
                                 && (policy.caseType() == null || sc.caseType().equals(policy.caseType()))
                                 && sc.storedAt().isBefore(cutoff));
        }
        if (policy.maxCasesPerType() != null) {
            var grouped = new java.util.LinkedHashMap<String, java.util.List<StoredCase>>();
            for (StoredCase sc : cases) {
                if (!sc.tenantId().equals(policy.tenantId()) || !sc.domain().equals(policy.domain())) {continue;}
                if (policy.caseType() != null && !sc.caseType().equals(policy.caseType())) {continue;}
                grouped.computeIfAbsent(sc.caseType(), k -> new java.util.ArrayList<>()).add(sc);
            }
            for (var entry : grouped.values()) {
                if (entry.size() > policy.maxCasesPerType()) {
                    entry.sort(java.util.Comparator.comparing(StoredCase::storedAt));
                    int excess = entry.size() - policy.maxCasesPerType();
                    for (int i = 0; i < excess; i++) {
                        cases.remove(entry.get(i));
                    }
                }
            }
        }
        if (policy.minTrustScore() != null) {
            cases.removeIf(sc -> sc.tenantId().equals(policy.tenantId())
                                 && sc.domain().equals(policy.domain())
                                 && (policy.caseType() == null || sc.caseType().equals(policy.caseType()))
                                 && sc.cbrCase().trustScore() != null
                                 && sc.cbrCase().trustScore() < policy.minTrustScore());
        }
        return before - cases.size();}

    @Override
    public java.util.Set<String> discoverTenants(io.casehub.neocortex.memory.MemoryDomain domain) {
        return cases.stream()
                    .filter(sc -> sc.domain().equals(domain))
                    .map(StoredCase::tenantId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public CbrScanResult scan(CbrScanRequest request) {
        boolean              pastCursor = request.cursor() == null;
        List<CbrCaseSummary> result     = new java.util.ArrayList<>();
        for (StoredCase sc : cases) {
            if (!sc.tenantId().equals(request.tenantId())) {continue;}
            if (!sc.domain().equals(request.domain())) {continue;}
            if (!sc.caseType().equals(request.caseType())) {continue;}
            if (!pastCursor) {
                if (sc.caseId().equals(request.cursor())) {pastCursor = true;}
                continue;
            }
            result.add(new CbrCaseSummary(
                    sc.caseId(), sc.subjectId(), sc.caseType(),
                    sc.cbrCase().producerAgentId(), sc.cbrCase().trustScore(), sc.storedAt()));
            if (result.size() >= request.limit()) {break;}
        }
        String nextCursor = result.size() >= request.limit() ? result.get(result.size() - 1).caseId() : null;
        return new CbrScanResult(result, nextCursor);
    }

    @Override
    public SupersessionStatus getSupersessionStatus(String caseId, String tenantId) {
        java.util.Objects.requireNonNull(caseId, "caseId required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        for (StoredCase stored : cases) {
            if (caseId.equals(stored.caseId()) && tenantId.equals(stored.tenantId())) {
                if (stored.supersededAt() != null) {
                    return new SupersessionStatus(caseId, true, stored.supersededAt(),
                            stored.supersedingCaseId(), stored.supersessionReason(), stored.reinstatedAt());
                }
                return new SupersessionStatus(caseId, false, null, null, null, stored.reinstatedAt());
            }
        }
        return SupersessionStatus.NOT_SUPERSEDED;
    }

    @Override
    public List<SupersessionStatus> findSupersededCases(String tenantId, MemoryDomain domain) {
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        java.util.Objects.requireNonNull(domain, "domain required");
        List<SupersessionStatus> result = new ArrayList<>();
        for (StoredCase stored : cases) {
            if (stored.tenantId().equals(tenantId) && stored.domain().equals(domain)
                    && stored.supersededAt() != null) {
                result.add(new SupersessionStatus(stored.caseId(), true, stored.supersededAt(),
                        stored.supersedingCaseId(), stored.supersessionReason(), stored.reinstatedAt()));
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public void supersede(String caseId, String tenantId, String supersedingCaseId, String reason) {
        java.util.Objects.requireNonNull(caseId, "caseId required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        for (int i = 0; i < cases.size(); i++) {
            StoredCase sc = cases.get(i);
            if (caseId.equals(sc.caseId()) && tenantId.equals(sc.tenantId())) {
                if (sc.supersededAt() != null) {
                    String newId = supersedingCaseId != null ? supersedingCaseId : sc.supersedingCaseId();
                    String newReason = reason != null ? reason : sc.supersessionReason();
                    cases.set(i, sc.withSupersession(sc.supersededAt(), newId, newReason));
                } else {
                    cases.set(i, sc.withSupersession(Instant.now(), supersedingCaseId, reason));
                }
                return;
            }
        }
    }

    @Override
    public void reinstate(String caseId, String tenantId) {
        java.util.Objects.requireNonNull(caseId, "caseId required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        for (int i = 0; i < cases.size(); i++) {
            StoredCase sc = cases.get(i);
            if (caseId.equals(sc.caseId()) && tenantId.equals(sc.tenantId())) {
                cases.set(i, sc.withReinstatement(Instant.now()));
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean matchesFilters(CbrCase storedCase, Map<String, CbrFilter> filters,
                                   CbrFeatureSchema schema) {
        if (filters.isEmpty()) {return true;}
        for (var entry : filters.entrySet()) {
            String       fieldName   = entry.getKey();
            CbrFilter    filter      = entry.getValue();
            FeatureValue storedValue = storedCase.features().get(fieldName);
            if (storedValue == null) {return false;}

            FeatureField field = CbrFeatureValidator.findField(schema, fieldName);

            if (!matchesSingleFilter(storedValue, filter, field)) {return false;}
        }
        return true;
    }

    private boolean matchesSingleFilter(FeatureValue storedValue, CbrFilter filter, FeatureField field) {
        return switch (filter) {
            case CbrFilter.Contains c -> (storedValue instanceof FeatureValue.StringListVal sl && sl.values().contains(c.value()))
                    || (storedValue instanceof FeatureValue.StringVal sv && sv.value().equals(c.value()));
            case CbrFilter.ContainsAll ca -> storedValue instanceof FeatureValue.StringListVal sl && sl.values().containsAll(ca.values());
            case CbrFilter.ContainsAny ca -> (storedValue instanceof FeatureValue.StringListVal sl && ca.values().stream().anyMatch(sl.values()::contains))
                    || (storedValue instanceof FeatureValue.StringVal sv && ca.values().contains(sv.value()));
            case CbrFilter.NotContains nc -> storedValue instanceof FeatureValue.StringListVal sl && !sl.values().contains(nc.value());
            case CbrFilter.NotContainsAny nca -> storedValue instanceof FeatureValue.StringListVal sl && nca.values().stream().noneMatch(sl.values()::contains);
            case CbrFilter.ContainsRange cr -> storedValue instanceof FeatureValue.NumberListVal nl && nl.values().stream()
                                                                                                         .anyMatch(n -> n >= cr.range().min() && n <= cr.range().max());
            case CbrFilter.HasMatch hm -> matchesHasMatch(storedValue, hm, field);
            case CbrFilter.AllOf allOf -> {
                for (CbrFilter inner : allOf.filters()) {
                    if (!matchesSingleFilter(storedValue, inner, field)) {yield false;}
                }
                yield true;
            }
        };
    }

    private boolean matchesHasMatch(FeatureValue storedValue, CbrFilter.HasMatch hm, FeatureField field) {
        if (field instanceof FeatureField.ObjectList) {
            if (!(storedValue instanceof FeatureValue.StructListVal sl)) {return false;}
            return sl.items().stream().anyMatch(item -> allSubFieldsMatch(item, hm.subFields()));
        } else {
            if (!(storedValue instanceof FeatureValue.StructVal sv)) {return false;}
            return allSubFieldsMatch(sv.fields(), hm.subFields());
        }
    }

    private boolean allSubFieldsMatch(Map<String, FeatureValue> stored, Map<String, FeatureValue> subFields) {
        for (var sub : subFields.entrySet()) {
            FeatureValue storedVal = stored.get(sub.getKey());
            if (storedVal == null) {return false;}
            FeatureValue queryVal = sub.getValue();
            if (queryVal instanceof FeatureValue.RangeVal range) {
                if (!(storedVal instanceof FeatureValue.NumberVal sn)) {return false;}
                if (sn.value() < range.min() || sn.value() > range.max()) {return false;}
            } else if (queryVal instanceof FeatureValue.NumberVal qn) {
                if (!(storedVal instanceof FeatureValue.NumberVal sn)) {return false;}
                if (Double.compare(qn.value(), sn.value()) != 0) {return false;}
            } else {
                if (!queryVal.equals(storedVal)) {return false;}
            }
        }
        return true;
    }


    private static boolean isVisibleAtScope(io.casehub.platform.api.path.Path storedScope,
                                            io.casehub.platform.api.path.Path queryScope) {
        return storedScope.equals(queryScope) || storedScope.isAncestorOf(queryScope);
    }

    private record StoredCase(
            String id, CbrCase cbrCase, String caseType, String subjectId, MemoryDomain domain,
            String tenantId, String caseId, Instant storedAt, Instant lastOutcomeAt,
            Instant supersededAt, String supersedingCaseId, String supersessionReason,
            io.casehub.platform.api.path.Path scope, Instant reinstatedAt
    ) {
        StoredCase withSupersession(Instant supersededAt, String supersedingCaseId, String supersessionReason) {
            return new StoredCase(id, cbrCase, caseType, subjectId, domain, tenantId, caseId, storedAt, lastOutcomeAt,
                                  supersededAt, supersedingCaseId, supersessionReason, scope, null);
        }
        StoredCase withReinstatement(Instant reinstatedAt) {
            return new StoredCase(id, cbrCase, caseType, subjectId, domain, tenantId, caseId, storedAt, lastOutcomeAt,
                                  null, null, null, scope, reinstatedAt);
        }
    }
}
