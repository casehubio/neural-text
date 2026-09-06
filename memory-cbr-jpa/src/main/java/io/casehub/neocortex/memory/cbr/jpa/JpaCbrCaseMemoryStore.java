package io.casehub.neocortex.memory.cbr.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrFeatureValidator;
import io.casehub.neocortex.memory.cbr.CbrFilter;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.CbrSimilarityScorer;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.SupersessionStatus;
import io.casehub.neocortex.memory.cbr.TextualCbrCase;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Alternative
@Priority(3)
@ApplicationScoped
public class JpaCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private static final Logger                             LOG             = Logger.getLogger(JpaCbrCaseMemoryStore.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE        = new TypeReference<>() {};
    private static final TypeReference<List<PlanTrace>>     PLAN_TRACE_TYPE = new TypeReference<>() {};

    private final Map<String, CbrFeatureSchema> schemas = new ConcurrentHashMap<>();

    @Inject
    EntityManager em;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public void registerSchema(CbrFeatureSchema schema) {
        schemas.put(schema.caseType(), schema);
    }

    @Override
    @Transactional
    public String store(CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
                        String tenantId, String caseId, io.casehub.platform.api.path.Path scope) {
        CbrFeatureSchema schema = schemas.get(caseType);
        if (schema != null) {
            CbrFeatureValidator.validateStoreFeatures(cbrCase.features(), schema);
        }

        CbrCaseEntity entity = new CbrCaseEntity();
        entity.id         = UUID.randomUUID().toString();
        entity.tenantId   = tenantId;
        entity.domain     = domain.name();
        entity.caseType   = caseType;
        entity.cbrType    = cbrCase.cbrType();
        entity.entityId   = entityId;
        entity.caseId     = caseId;
        entity.problem    = cbrCase.problem();
        entity.solution   = cbrCase.solution();
        entity.outcome    = cbrCase.outcome();
        entity.confidence = cbrCase.confidence() != null ? cbrCase.confidence().value() : null;
        entity.features   = serializeJson(FeatureValue.toRawMap(cbrCase.features()));
        entity.storedAt        = Instant.now();
        entity.scope           = scope.value();
        entity.trustScore      = cbrCase.trustScore();
        entity.producerAgentId = cbrCase.producerAgentId();

        if (cbrCase instanceof PlanCbrCase plan && !plan.planTrace().isEmpty()) {
            entity.planTraces = serializeJson(plan.planTrace());
        }

        em.persist(entity);
        return entity.id;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> caseClass) {
        if (query.retrievalMode() == RetrievalMode.SEMANTIC_ONLY) {
            return List.of();
        }
        if (query.retrievalMode() == RetrievalMode.HYBRID && query.problem() != null) {
            LOG.info("HYBRID mode degraded to FEATURE_ONLY — no EmbeddingModel available");
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

        String scopeVal = query.scope().value();
        String jpql = "SELECT e FROM CbrCaseEntity e WHERE e.tenantId = :t AND e.domain = :d AND e.caseType = :ct AND e.supersededAt IS NULL"
                      + " AND (e.scope = '' OR e.scope = :scopeVal OR :scopeVal LIKE CONCAT(e.scope, '/%'))"
                      + (query.notBefore() != null ? " AND e.storedAt >= :nb" : "");

        var jpaQuery = em.createQuery(jpql, CbrCaseEntity.class)
                         .setParameter("t", query.tenantId())
                         .setParameter("d", query.domain().name())
                         .setParameter("ct", query.caseType())
                         .setParameter("scopeVal", scopeVal);
        if (query.notBefore() != null) {
            jpaQuery.setParameter("nb", query.notBefore());
        }

        List<CbrCaseEntity>    entities   = jpaQuery.getResultList();
        List<ScoredCbrCase<C>> candidates = new ArrayList<>();

        for (CbrCaseEntity entity : entities) {
            CbrCase reconstructed = reconstruct(entity);
            if (!caseClass.isInstance(reconstructed)) {continue;}

            if (!matchesFilters(reconstructed, query.filters(), schema)) {continue;}

            CbrSimilarityScorer.SimilarityBreakdown breakdown = CbrSimilarityScorer.scoreDetailed(
                    query.features(), reconstructed.features(), query.weights(), schema, Map.of());

            double score = breakdown.score();
            if (score >= query.minSimilarity()) {
                io.casehub.platform.api.path.Path entityScope = entity.scope.isEmpty()
                        ? io.casehub.platform.api.path.Path.root()
                        : io.casehub.platform.api.path.Path.parse(entity.scope);
                candidates.add(new ScoredCbrCase<>((C) reconstructed, entity.caseId,
                                                   score, false, breakdown.featureSimilarities(), entity.storedAt, entityScope, null));
            }
        }

        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<ScoredCbrCase<C>> results = candidates.size() <= query.topK()
                                         ? candidates
                                         : candidates.subList(0, query.topK());
        return Collections.unmodifiableList(new ArrayList<>(results));
    }

    @Override
    @Transactional
    public Integer erase(EraseRequest request) {
        String jpql = "DELETE FROM CbrCaseEntity e WHERE e.entityId = :eid AND e.domain = :d AND e.tenantId = :t";
        if (request.caseId() != null) {
            jpql += " AND e.caseId = :cid";
        }
        var q = em.createQuery(jpql)
                  .setParameter("eid", request.entityId())
                  .setParameter("d", request.domain().name())
                  .setParameter("t", request.tenantId());
        if (request.caseId() != null) {
            q.setParameter("cid", request.caseId());
        }
        return q.executeUpdate();
    }

    @Override
    @Transactional
    public Integer eraseEntity(String entityId, String tenantId) {
        return em.createQuery("DELETE FROM CbrCaseEntity e WHERE e.entityId = :eid AND e.tenantId = :t")
                 .setParameter("eid", entityId)
                 .setParameter("t", tenantId)
                 .executeUpdate();
    }

    @Override
    @Transactional
    public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {
        java.util.Objects.requireNonNull(scope, "scope required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        if (scope.segments().isEmpty()) {
            return em.createQuery("DELETE FROM CbrCaseEntity e WHERE e.tenantId = :t")
                     .setParameter("t", tenantId)
                     .executeUpdate();
        }
        return em.createQuery("DELETE FROM CbrCaseEntity e WHERE e.tenantId = :t AND (e.scope = :s OR e.scope LIKE :prefix)")
                 .setParameter("t", tenantId)
                 .setParameter("s", scope.value())
                 .setParameter("prefix", scope.value() + "/%")
                 .executeUpdate();
    }


    @Override
    @Transactional
    public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        var results = em.createQuery(
                                "SELECT c FROM CbrCaseEntity c WHERE c.caseId = :caseId AND c.tenantId = :tenantId",
                                CbrCaseEntity.class)
                        .setParameter("caseId", caseId)
                        .setParameter("tenantId", tenantId)
                        .getResultList();
        if (results.isEmpty()) {return;}
        CbrCaseEntity entity = results.getFirst();
        if (entity.lastOutcomeAt != null
            && !outcome.observedAt().isAfter(entity.lastOutcomeAt)) {
            return;
        }
        CbrFeatureSchema schema = schemas.get(entity.caseType);
        double lr = (schema != null && schema.learningRate() != null)
                    ? schema.learningRate() : CbrOutcome.DEFAULT_LEARNING_RATE;
        io.casehub.neocortex.cognitive.Confidence newConf = CbrOutcome.adjustConfidence(
                entity.confidence != null ? io.casehub.neocortex.cognitive.Confidence.unknown(entity.confidence) : null,
                outcome.successRate(), lr);
        entity.outcome       = outcome.result().name();
        entity.confidence    = newConf.value();
        entity.outcomeDetail = outcome.detail();
        entity.lastOutcomeAt = outcome.observedAt();
        em.merge(entity);
    }

    @Override
    @Transactional
    public Integer purge(CbrRetentionPolicy policy) {
        int    deleted        = 0;
        String caseTypeFilter = policy.caseType() != null ? " AND e.caseType = :ct" : "";
        if (policy.maxAgeDays() != null) {
            Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(policy.maxAgeDays()));
            String  jpql   = "DELETE FROM CbrCaseEntity e WHERE e.tenantId = :t AND e.domain = :d AND e.storedAt < :cutoff" + caseTypeFilter;
            var     q      = em.createQuery(jpql).setParameter("t", policy.tenantId()).setParameter("d", policy.domain().name()).setParameter("cutoff", cutoff);
            if (policy.caseType() != null) {q.setParameter("ct", policy.caseType());}
            deleted += q.executeUpdate();
        }
        if (policy.maxCasesPerType() != null) {
            String typeJpql  = "SELECT DISTINCT e.caseType FROM CbrCaseEntity e WHERE e.tenantId = :t AND e.domain = :d" + caseTypeFilter;
            var    typeQuery = em.createQuery(typeJpql, String.class).setParameter("t", policy.tenantId()).setParameter("d", policy.domain().name());
            if (policy.caseType() != null) {typeQuery.setParameter("ct", policy.caseType());}
            for (String ct : typeQuery.getResultList()) {
                var ids = em.createQuery("SELECT e.id FROM CbrCaseEntity e WHERE e.tenantId = :t AND e.domain = :d AND e.caseType = :ct ORDER BY e.storedAt DESC", String.class)
                            .setParameter("t", policy.tenantId()).setParameter("d", policy.domain().name()).setParameter("ct", ct).getResultList();
                if (ids.size() > policy.maxCasesPerType()) {
                    List<String> toDelete = ids.subList(policy.maxCasesPerType(), ids.size());
                    deleted += em.createQuery("DELETE FROM CbrCaseEntity e WHERE e.id IN :ids").setParameter("ids", toDelete).executeUpdate();
                }
            }
        }
        if (policy.minTrustScore() != null) {
            String jpql = "DELETE FROM CbrCaseEntity e WHERE e.tenantId = :t AND e.domain = :d AND e.trustScore IS NOT NULL AND e.trustScore < :minTrust" + caseTypeFilter;
            var    q    = em.createQuery(jpql).setParameter("t", policy.tenantId()).setParameter("d", policy.domain().name()).setParameter("minTrust", policy.minTrustScore());
            if (policy.caseType() != null) {q.setParameter("ct", policy.caseType());}
            deleted += q.executeUpdate();
        }
        return deleted;}

    @Override
    @Transactional
    public java.util.Set<String> discoverTenants(io.casehub.neocortex.memory.MemoryDomain domain) {
        return new java.util.LinkedHashSet<>(
                em.createQuery("SELECT DISTINCT e.tenantId FROM CbrCaseEntity e WHERE e.domain = :d", String.class)
                  .setParameter("d", domain.name())
                  .getResultList());
    }

    @Override
    public io.casehub.neocortex.memory.cbr.CbrScanResult scan(io.casehub.neocortex.memory.cbr.CbrScanRequest request) {
        String jpql = "SELECT e FROM CbrCaseEntity e WHERE e.tenantId = :t AND e.domain = :d AND e.caseType = :ct"
                      + (request.cursor() != null ? " AND e.caseId > :afterId" : "")
                      + " ORDER BY e.caseId";
        var q = em.createQuery(jpql, CbrCaseEntity.class)
                  .setParameter("t", request.tenantId())
                  .setParameter("d", request.domain().name())
                  .setParameter("ct", request.caseType())
                  .setMaxResults(request.limit());
        if (request.cursor() != null) {q.setParameter("afterId", request.cursor());}
        var items = q.getResultList().stream()
                     .map(e -> new io.casehub.neocortex.memory.cbr.CbrCaseSummary(
                             e.caseId, e.entityId, e.caseType, e.producerAgentId, e.trustScore, e.storedAt))
                     .toList();
        String nextCursor = items.size() >= request.limit() ? items.get(items.size() - 1).caseId() : null;
        return new io.casehub.neocortex.memory.cbr.CbrScanResult(items, nextCursor);
    }


    @Override
    @Transactional
    public boolean supersede(String caseId, String tenantId, String supersedingCaseId, String reason) {
        java.util.Objects.requireNonNull(caseId, "caseId required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        var results = em.createQuery("SELECT e FROM CbrCaseEntity e WHERE e.caseId = :cid AND e.tenantId = :t", CbrCaseEntity.class)
                        .setParameter("cid", caseId).setParameter("t", tenantId).getResultList();
        if (results.isEmpty()) return false;
        CbrCaseEntity entity = results.getFirst();
        if (entity.supersededAt != null) {
            return false;
        }
        entity.supersededAt = Instant.now();
        entity.supersedingCaseId = supersedingCaseId;
        entity.supersessionReason = reason;
        entity.reinstatedAt = null;
        return true;
    }

    @Override
    @Transactional
    public boolean reinstate(String caseId, String tenantId) {
        java.util.Objects.requireNonNull(caseId, "caseId required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        var results = em.createQuery("SELECT e FROM CbrCaseEntity e WHERE e.caseId = :cid AND e.tenantId = :t", CbrCaseEntity.class)
                        .setParameter("cid", caseId).setParameter("t", tenantId).getResultList();
        if (results.isEmpty()) return false;
        CbrCaseEntity entity = results.getFirst();
        if (entity.supersededAt == null) {
            return false;
        }
        entity.reinstatedAt = Instant.now();
        entity.supersededAt = null;
        entity.supersedingCaseId = null;
        entity.supersessionReason = null;
        return true;
    }

    @Override
    public List<String> findCaseIds(String tenantId, MemoryDomain domain,
                                     String caseType, Map<String, CbrFilter> filters) {
        Objects.requireNonNull(tenantId, "tenantId required");
        Objects.requireNonNull(domain, "domain required");
        Objects.requireNonNull(caseType, "caseType required");
        Objects.requireNonNull(filters, "filters required");

        CbrFeatureSchema schema = schemas.get(caseType);
        if (!filters.isEmpty()) {
            if (schema == null) {
                throw new IllegalStateException(
                        "Cannot apply filters: no schema registered for caseType '" + caseType + "'");
            }
            CbrFeatureValidator.validateFilters(filters, schema);
        }

        var entities = em.createQuery(
                        "SELECT e FROM CbrCaseEntity e WHERE e.tenantId = :t AND e.domain = :d AND e.caseType = :ct AND e.supersededAt IS NULL",
                        CbrCaseEntity.class)
                .setParameter("t", tenantId).setParameter("d", domain.name()).setParameter("ct", caseType)
                .getResultList();

        List<String> result = new ArrayList<>();
        for (CbrCaseEntity entity : entities) {
            if (filters.isEmpty() || matchesFilters(reconstruct(entity), filters, schema)) {
                result.add(entity.caseId);
            }
        }
        return result;
    }

    @Override
    @Transactional
    public int supersedeMatching(String tenantId, MemoryDomain domain, String caseType,
                                  Map<String, CbrFilter> filters, String reason) {
        List<String> ids = findCaseIds(tenantId, domain, caseType, filters);
        if (ids.isEmpty()) return 0;
        int count = 0;
        for (String id : ids) {
            if (supersede(id, tenantId, null, reason)) {
                count++;
            }
        }
        return count;
    }

    @Override
    @Transactional
    public int supersedeAll(Collection<String> caseIds, String tenantId, String reason) {
        Objects.requireNonNull(caseIds, "caseIds required");
        Objects.requireNonNull(tenantId, "tenantId required");
        int count = 0;
        for (String caseId : caseIds) {
            if (supersede(caseId, tenantId, null, reason)) {
                count++;
            }
        }
        return count;
    }

    @Override
    @Transactional
    public int reinstateMatching(String tenantId, MemoryDomain domain, String caseType,
                                  Map<String, CbrFilter> filters) {
        Objects.requireNonNull(tenantId, "tenantId required");
        Objects.requireNonNull(domain, "domain required");
        Objects.requireNonNull(caseType, "caseType required");
        Objects.requireNonNull(filters, "filters required");

        CbrFeatureSchema schema = schemas.get(caseType);
        if (!filters.isEmpty()) {
            if (schema == null) {
                throw new IllegalStateException(
                        "Cannot apply filters: no schema registered for caseType '" + caseType + "'");
            }
            CbrFeatureValidator.validateFilters(filters, schema);
        }

        var entities = em.createQuery(
                        "SELECT e FROM CbrCaseEntity e WHERE e.tenantId = :t AND e.domain = :d AND e.caseType = :ct AND e.supersededAt IS NOT NULL",
                        CbrCaseEntity.class)
                .setParameter("t", tenantId).setParameter("d", domain.name()).setParameter("ct", caseType)
                .getResultList();

        int count = 0;
        for (CbrCaseEntity entity : entities) {
            if (filters.isEmpty() || matchesFilters(reconstruct(entity), filters, schema)) {
                if (reinstate(entity.caseId, tenantId)) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    @Transactional
    public int reinstateAll(Collection<String> caseIds, String tenantId) {
        Objects.requireNonNull(caseIds, "caseIds required");
        Objects.requireNonNull(tenantId, "tenantId required");
        int count = 0;
        for (String caseId : caseIds) {
            if (reinstate(caseId, tenantId)) {
                count++;
            }
        }
        return count;
    }

    private CbrCase reconstruct(CbrCaseEntity entity) {
        Map<String, FeatureValue> features = deserializeFeatures(entity.features);
        io.casehub.neocortex.cognitive.Confidence confidence = entity.confidence != null
                ? io.casehub.neocortex.cognitive.Confidence.unknown(entity.confidence) : null;
        return switch (entity.cbrType) {
            case "plan" -> new PlanCbrCase(
                    entity.problem, entity.solution, entity.outcome, confidence,
                    features, deserializePlanTraces(entity.planTraces), entity.trustScore, entity.producerAgentId);
            case "feature-vector" -> new FeatureVectorCbrCase(
                    entity.problem, entity.solution, entity.outcome, confidence, features, entity.trustScore, entity.producerAgentId);
            case "textual" -> new TextualCbrCase(
                    entity.problem, entity.solution, entity.outcome, confidence, entity.trustScore, entity.producerAgentId);
            default -> new FeatureVectorCbrCase(
                    entity.problem, entity.solution, entity.outcome, confidence, features, entity.trustScore, entity.producerAgentId);
        };}

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
            return sl.items().stream().anyMatch(elem -> allSubFieldsMatch(elem, hm.subFields()));
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
                if (!(storedVal instanceof FeatureValue.NumberVal num)) {return false;}
                double d = num.value();
                if (d < range.min() || d > range.max()) {return false;}
            } else if (queryVal instanceof FeatureValue.NumberVal qn) {
                if (!(storedVal instanceof FeatureValue.NumberVal sn)) {return false;}
                if (Double.compare(qn.value(), sn.value()) != 0) {return false;}
            } else {
                if (!queryVal.equals(storedVal)) {return false;}
            }
        }
        return true;
    }

    private String serializeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize to JSON", e);
        }
    }

    private Map<String, FeatureValue> deserializeFeatures(String json) {
        if (json == null || json.isBlank()) {return Map.of();}
        try {
            Map<String, Object> raw = objectMapper.readValue(json, MAP_TYPE);
            return FeatureValue.toFeatureMap(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize features JSON", e);
        }
    }

    private List<PlanTrace> deserializePlanTraces(String json) {
        if (json == null || json.isBlank()) {return List.of();}
        try {
            return objectMapper.readValue(json, PLAN_TRACE_TYPE);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to deserialize plan traces: %s", e.getMessage());
            return List.of();
        }
    }

    @Override
    public SupersessionStatus getSupersessionStatus(String caseId, String tenantId) {
        java.util.Objects.requireNonNull(caseId, "caseId required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        var results = em.createQuery("SELECT e FROM CbrCaseEntity e WHERE e.caseId = :cid AND e.tenantId = :t", CbrCaseEntity.class)
                        .setParameter("cid", caseId).setParameter("t", tenantId).getResultList();
        if (results.isEmpty()) return SupersessionStatus.NOT_SUPERSEDED;
        CbrCaseEntity entity = results.getFirst();
        if (entity.supersededAt != null) {
            return new SupersessionStatus(caseId, true, entity.supersededAt,
                    entity.supersedingCaseId, entity.supersessionReason, entity.reinstatedAt);
        }
        return new SupersessionStatus(caseId, false, null, null, null, entity.reinstatedAt);
    }

    @Override
    public List<SupersessionStatus> findSupersededCases(String tenantId, MemoryDomain domain) {
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        java.util.Objects.requireNonNull(domain, "domain required");
        var results = em.createQuery(
                "SELECT e FROM CbrCaseEntity e WHERE e.tenantId = :t AND e.domain = :d AND e.supersededAt IS NOT NULL",
                CbrCaseEntity.class)
                .setParameter("t", tenantId).setParameter("d", domain.name()).getResultList();
        return results.stream().map(e -> new SupersessionStatus(e.caseId, true, e.supersededAt,
                e.supersedingCaseId, e.supersessionReason, e.reinstatedAt)).toList();
    }

}
