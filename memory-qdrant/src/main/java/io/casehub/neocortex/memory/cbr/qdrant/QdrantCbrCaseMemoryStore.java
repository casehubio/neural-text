package io.casehub.neocortex.memory.cbr.qdrant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ListenableFuture;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.neocortex.fusion.CamelCaseExpander;
import io.casehub.neocortex.fusion.FusionStrategy;
import io.casehub.neocortex.fusion.ScoreFusion;
import io.casehub.neocortex.inference.splade.SparseEmbedder;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrFeatureValidator;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.CbrSimilarityScorer;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.LocalSimilarityFunction;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.SupersessionStatus;
import io.casehub.neocortex.memory.cbr.TextualCbrCase;
import io.casehub.neocortex.memory.cbr.embedding.EmbeddingTextSimilarity;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Common.Range;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class QdrantCbrCaseMemoryStore implements CbrCaseMemoryStore {

    private static final Logger LOG = Logger.getLogger(QdrantCbrCaseMemoryStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<PlanTrace>> PLAN_TRACE_TYPE = new TypeReference<>() {};

    private final CbrCollectionManager collectionManager;
    private final EmbeddingModel embeddingModel;
    private final SparseEmbedder sparseEmbedder;
    private final QdrantCbrConfig config;
    private final CaseMemoryStore delegate;
    private final Map<String, CbrFeatureSchema> schemas = new ConcurrentHashMap<>();

    @Inject
    QdrantCbrCaseMemoryStore(CbrCollectionManager collectionManager,
                             Instance<EmbeddingModel> embeddingModelInstance,
                             QdrantCbrConfig config,
                             Instance<CaseMemoryStore> delegateInstance,
                             Instance<SparseEmbedder> sparseEmbedderInstance) {
        this.collectionManager = collectionManager;
        this.embeddingModel = embeddingModelInstance.isResolvable() ? embeddingModelInstance.get() : null;
        this.config = config;
        this.delegate = delegateInstance.isResolvable() ? delegateInstance.get() : null;
        this.sparseEmbedder = sparseEmbedderInstance.isResolvable() ? sparseEmbedderInstance.get() : null;
    }

    QdrantCbrCaseMemoryStore(CbrCollectionManager collectionManager,
                             EmbeddingModel embeddingModel,
                             QdrantCbrConfig config,
                             CaseMemoryStore delegate,
                             SparseEmbedder sparseEmbedder) {
        this.collectionManager = collectionManager;
        this.embeddingModel = embeddingModel;
        this.config = config;
        this.delegate = delegate;
        this.sparseEmbedder = sparseEmbedder;
    }

    private static <T> T awaitFuture(ListenableFuture<T> future, String operation) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during " + operation, e);
        } catch (ExecutionException e) {
            throw new RuntimeException(operation + " failed", e.getCause());
        }
    }

    private static String extractString(Map<String, Value> payload, String key) {
        Value v = payload.get(key);
        if (v != null && v.hasStringValue()) return v.getStringValue();
        return null;
    }

    private static Instant extractStoredAt(Map<String, Value> payload) {
        Value v = payload.get("_stored_at");
        if (v != null && v.hasIntegerValue()) {
            long millis = v.getIntegerValue();
            return millis > 0 ? Instant.ofEpochMilli(millis) : null;
        }
        return null;
    }

    private static io.casehub.platform.api.path.Path extractScope(Map<String, Value> payload) {
        String sv = extractString(payload, "scope");
        return (sv == null || sv.isEmpty()) ? io.casehub.platform.api.path.Path.root()
                                            : io.casehub.platform.api.path.Path.parse(sv);
    }

    private static Double extractDouble(Map<String, Value> payload, String key) {
        Value v = payload.get(key);
        if (v != null && v.hasDoubleValue()) return v.getDoubleValue();
        return null;
    }

    @Override
    public void registerSchema(CbrFeatureSchema schema) {
        schemas.put(schema.caseType(), schema);
        collectionManager.registerSchemaIndexes(schema, vectorDimension());
    }

    @Override
    public String store(CbrCase cbrCase, String caseType, String entityId,
                        MemoryDomain domain, String tenantId, String caseId,
                        io.casehub.platform.api.path.Path scope) {
        CbrFeatureSchema schema = schemas.get(caseType);
        if (schema != null) {
            CbrFeatureValidator.validateStoreFeatures(cbrCase.features(), schema);
        }

        String mid = caseId;
        if (delegate != null) {
            MemoryInput memoryInput = CbrMemorySerializer.serialize(
                    cbrCase, entityId, domain, tenantId, caseId, caseType);
            mid = delegate.store(memoryInput);
        }

        Embedding embedding = null;
        if (embeddingModel != null) {
            embedding = embeddingModel.embed(TextSegment.from(cbrCase.problem())).content();
        }

        Map<Integer, Float> sparseEmbedding = null;
        if (sparseEmbedder != null && config.spladeEnabled() && cbrCase.problem() != null) {
            sparseEmbedding = sparseEmbedder.embed(cbrCase.problem());
        }

        String bm25Text = null;
        if (config.bm25Enabled() && cbrCase.problem() != null) {
            bm25Text = CamelCaseExpander.expand(cbrCase.problem());
        }

        PointStruct point = CbrPointBuilder.buildPoint(
                cbrCase, caseType, entityId, domain.name(), tenantId, caseId,
                embedding, config.denseVectorName(),
                sparseEmbedding, config.spladeVectorName(),
                bm25Text, config.bm25VectorName(), config.bm25Model(), scope.value());

        String collection = collectionManager.collectionName(caseType);
        collectionManager.ensureCollection(caseType, vectorDimension());
        upsertWithRetry(collection, List.of(point), config.maxRetries());
        return mid;
    }

    private void upsertWithRetry(String collection, List<PointStruct> points, int maxRetries) {
        int attempts = 0;
        while (true) {
            try {
                awaitFuture(collectionManager.client().upsertAsync(collection, points), "upsert");
                return;
            } catch (RuntimeException e) {
                attempts++;
                if (attempts >= maxRetries) throw e;
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
            CbrQuery query, Class<C> caseClass) {
        CbrFeatureSchema schema = schemas.get(query.caseType());
        if (schema != null) {
            CbrQueryTranslator.validateQueryFeatures(query.features(), schema);
        }
        if (!query.filters().isEmpty() && schema == null) {
            throw new IllegalStateException(
                "Cannot apply structural filters: no schema registered for caseType '"
                + query.caseType() + "'");
        }

        String collection = collectionManager.collectionName(query.caseType());

        boolean exists = awaitFuture(
            collectionManager.client().collectionExistsAsync(collection), "collectionExists");
        if (!exists) return List.of();

        Filter filter = CbrQueryTranslator.toIdentityFilter(query);
        if (!query.filters().isEmpty()) {
            filter = CbrQueryTranslator.applyStructuralFilters(filter, query.filters(), schema);
        }
        RetrievalMode effectiveMode = resolveEffectiveMode(query);
        if (effectiveMode == null) return List.of();

        return switch (effectiveMode) {
            case FEATURE_ONLY -> retrieveFeatureOnly(query, caseClass, collection, filter, schema);
            case SEMANTIC_ONLY -> retrieveSemanticOnly(query, caseClass, collection, filter);
            case HYBRID -> retrieveHybrid(query, caseClass, collection, filter, schema);
        };
    }

    private RetrievalMode resolveEffectiveMode(CbrQuery query) {
        return switch (query.retrievalMode()) {
            case SEMANTIC_ONLY -> {
                if (embeddingModel == null || query.problem() == null) {
                    LOG.warning("SEMANTIC_ONLY unavailable — " +
                        (embeddingModel == null ? "no EmbeddingModel" : "problem is null"));
                    yield null;
                }
                yield RetrievalMode.SEMANTIC_ONLY;
            }
            case HYBRID -> {
                if (embeddingModel == null || query.problem() == null) {
                    LOG.warning("HYBRID degraded to FEATURE_ONLY — " +
                        (embeddingModel == null ? "no EmbeddingModel" : "problem is null"));
                    yield RetrievalMode.FEATURE_ONLY;
                }
                yield RetrievalMode.HYBRID;
            }
            case FEATURE_ONLY -> RetrievalMode.FEATURE_ONLY;
        };
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> List<ScoredCbrCase<C>> retrieveFeatureOnly(
            CbrQuery query, Class<C> caseClass, String collection, Filter filter,
            CbrFeatureSchema schema) {
        List<ScoredPoint> scoredPoints = executeFilterQuery(collection, filter,
            Math.max(query.topK(), config.overFetchLimit()));

        EmbeddingTextSimilarity textSim = (schema != null && embeddingModel != null)
            ? new EmbeddingTextSimilarity(embeddingModel) : null;
        Map<String, LocalSimilarityFunction> overrides = schema != null
            ? buildTextOverrides(schema, textSim) : Map.of();

        List<ReconstructedCandidate<C>> reconstructed = reconstructAll(scoredPoints, caseClass);

        if (textSim != null && !overrides.isEmpty()) {
            textSim.precompute(collectSemanticTextValues(query, reconstructed, overrides.keySet()));
        }

        List<ScoredCbrCase<C>> candidates = new ArrayList<>(reconstructed.size());
        for (var rc : reconstructed) {
            double score = CbrSimilarityScorer.score(
                query.features(), rc.cbrCase().features(), query.weights(), schema, overrides);
            if (score >= query.minSimilarity()) {
                candidates.add(new ScoredCbrCase<>(rc.cbrCase(), rc.caseId(), score, false, Map.of(), rc.storedAt(), rc.scope(), null));
            }
        }
        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
        return trimToTopK(candidates, query.topK());
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSemanticOnly(
            CbrQuery query, Class<C> caseClass, String collection, Filter filter) {
        Embedding queryEmbedding = embeddingModel.embed(TextSegment.from(query.problem())).content();

        SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
            .setCollectionName(collection)
            .addAllVector(queryEmbedding.vectorAsList())
            .setVectorName(config.denseVectorName())
            .setFilter(filter)
            .setLimit(query.topK() * config.oversampleFactor())
            .setWithPayload(WithPayloadSelectorFactory.enable(true));
        List<ScoredPoint> scoredPoints = awaitFuture(
            collectionManager.client().searchAsync(searchBuilder.build()), "semanticSearch");

        List<ScoredCbrCase<C>> candidates = new ArrayList<>(scoredPoints.size());
        for (ScoredPoint point : scoredPoints) {
            try {
                C cbrCase = (C) reconstructCase(point.getPayloadMap(), caseClass);
                if (cbrCase != null && point.getScore() >= query.minSimilarity()) {
                    String caseId = extractString(point.getPayloadMap(), "caseId");
                    Instant storedAt = extractStoredAt(point.getPayloadMap());
                    candidates.add(new ScoredCbrCase<>(cbrCase, caseId, point.getScore(), false, Map.of(), storedAt, extractScope(point.getPayloadMap()), null));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to reconstruct case from point", e);
            }
        }
        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
        return trimToTopK(candidates, query.topK());
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> List<ScoredCbrCase<C>> retrieveHybrid(
            CbrQuery query, Class<C> caseClass, String collection, Filter filter,
            CbrFeatureSchema schema) {

        Embedding denseEmbedding = (embeddingModel != null)
            ? embeddingModel.embed(TextSegment.from(query.problem())).content() : null;

        Map<Integer, Float> sparseEmbedding = (sparseEmbedder != null && config.spladeEnabled() && query.problem() != null)
            ? sparseEmbedder.embed(query.problem()) : null;

        List<ScoredPoint> densePoints = List.of();
        if (denseEmbedding != null) {
            try { densePoints = executeDenseSearch(collection, filter, query, denseEmbedding); }
            catch (Exception e) { LOG.log(Level.WARNING, "Dense search failed — skipping", e); }
        }

        List<ScoredPoint> spladePoints = List.of();
        if (sparseEmbedding != null) {
            try { spladePoints = executeSpladeSearch(collection, filter, query, sparseEmbedding); }
            catch (Exception e) { LOG.log(Level.WARNING, "SPLADE search failed — skipping", e); }
        }

        List<ScoredPoint> bm25Points = List.of();
        if (config.bm25Enabled() && query.problem() != null) {
            try { bm25Points = executeBm25Search(collection, filter, query); }
            catch (Exception e) { LOG.log(Level.WARNING, "BM25 search failed — skipping", e); }
        }

        List<ScoredPoint> filterPoints;
        try {
            filterPoints = executeFilterQuery(collection, filter,
                Math.max(query.topK(), config.overFetchLimit()));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Filter query failed — skipping", e);
            filterPoints = List.of();
        }

        Map<String, ReconstructedCandidate<C>> candidateMap = new LinkedHashMap<>();
        mergePoints(densePoints, candidateMap, caseClass, true);
        mergePoints(spladePoints, candidateMap, caseClass, false);
        mergePoints(bm25Points, candidateMap, caseClass, false);
        mergePoints(filterPoints, candidateMap, caseClass, false);

        List<ReconstructedCandidate<C>> allCandidates = new ArrayList<>(candidateMap.values());

        EmbeddingTextSimilarity textSim = (schema != null && embeddingModel != null)
            ? new EmbeddingTextSimilarity(embeddingModel) : null;
        Map<String, LocalSimilarityFunction> overrides = schema != null
            ? buildTextOverrides(schema, textSim) : Map.of();
        if (textSim != null && !overrides.isEmpty()) {
            textSim.precompute(collectSemanticTextValues(query, allCandidates, overrides.keySet()));
        }

        return fuseAndScore(query, allCandidates, overrides, schema,
            densePoints, spladePoints, bm25Points);
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> List<ScoredCbrCase<C>> fuseAndScore(
            CbrQuery query, List<ReconstructedCandidate<C>> allCandidates,
            Map<String, LocalSimilarityFunction> overrides, CbrFeatureSchema schema,
            List<ScoredPoint> densePoints, List<ScoredPoint> spladePoints,
            List<ScoredPoint> bm25Points) {

        record FusionEntry<C extends CbrCase>(String pointId, C cbrCase, double score,
                                              String caseId, Instant storedAt,
                                              io.casehub.platform.api.path.Path scope) {}

        List<FusionEntry<C>> featureLeg = new ArrayList<>();
        for (var rc : allCandidates) {
            double featureScore = CbrSimilarityScorer.score(
                query.features(), rc.cbrCase().features(), query.weights(), schema, overrides);
            featureLeg.add(new FusionEntry<>(rc.pointId(), rc.cbrCase(), featureScore, rc.caseId(), rc.storedAt(), rc.scope()));
        }

        List<FusionEntry<C>> denseLeg = new ArrayList<>();
        for (var rc : allCandidates) {
            if (rc.vectorScore() > 0) {
                denseLeg.add(new FusionEntry<>(rc.pointId(), rc.cbrCase(), rc.vectorScore(), rc.caseId(), rc.storedAt(), rc.scope()));
            }
        }

        Map<String, Float> spladeScores = new HashMap<>();
        for (var sp : spladePoints) spladeScores.put(sp.getId().getUuid(), sp.getScore());
        List<FusionEntry<C>> spladeLeg = new ArrayList<>();
        for (var rc : allCandidates) {
            Float score = spladeScores.get(rc.pointId());
            if (score != null && score > 0) {
                spladeLeg.add(new FusionEntry<>(rc.pointId(), rc.cbrCase(), score, rc.caseId(), rc.storedAt(), rc.scope()));
            }
        }

        Map<String, Float> bm25Scores = new HashMap<>();
        for (var sp : bm25Points) bm25Scores.put(sp.getId().getUuid(), sp.getScore());
        List<FusionEntry<C>> bm25Leg = new ArrayList<>();
        for (var rc : allCandidates) {
            Float score = bm25Scores.get(rc.pointId());
            if (score != null && score > 0) {
                bm25Leg.add(new FusionEntry<>(rc.pointId(), rc.cbrCase(), score, rc.caseId(), rc.storedAt(), rc.scope()));
            }
        }

        java.util.function.Function<FusionEntry<C>, String> idExtractor = FusionEntry::pointId;
        boolean useEqualWeights = query.fusionStrategy() == FusionStrategy.RRF;

        double featureWeight = useEqualWeights ? 1.0 : (1.0 - query.vectorWeight());
        var featureScoredLeg = new ScoreFusion.ScoredLeg<>(featureLeg, FusionEntry::score, featureWeight);

        double rawDense, rawSparse, rawBm25;
        if (useEqualWeights) {
            rawDense = (!denseLeg.isEmpty()) ? 1.0 : 0.0;
            rawSparse = (!spladeLeg.isEmpty()) ? 1.0 : 0.0;
            rawBm25 = (!bm25Leg.isEmpty()) ? 1.0 : 0.0;
        } else {
            rawDense = (!denseLeg.isEmpty()) ? config.ccWeights().dense() : 0.0;
            rawSparse = (!spladeLeg.isEmpty()) ? config.ccWeights().sparse() : 0.0;
            rawBm25 = (!bm25Leg.isEmpty()) ? config.ccWeights().bm25() : 0.0;
        }
        double semanticTotal = rawDense + rawSparse + rawBm25;

        List<ScoreFusion.ScoredLeg<FusionEntry<C>>> legs = new ArrayList<>();
        legs.add(featureScoredLeg);
        if (!denseLeg.isEmpty() && semanticTotal > 0) {
            double denseWeight = useEqualWeights ? 1.0 : query.vectorWeight() * rawDense / semanticTotal;
            legs.add(new ScoreFusion.ScoredLeg<>(denseLeg, FusionEntry::score, denseWeight));
        }
        if (!spladeLeg.isEmpty() && semanticTotal > 0) {
            double sparseWeight = useEqualWeights ? 1.0 : query.vectorWeight() * rawSparse / semanticTotal;
            legs.add(new ScoreFusion.ScoredLeg<>(spladeLeg, FusionEntry::score, sparseWeight));
        }
        if (!bm25Leg.isEmpty() && semanticTotal > 0) {
            double bm25Weight = useEqualWeights ? 1.0 : query.vectorWeight() * rawBm25 / semanticTotal;
            legs.add(new ScoreFusion.ScoredLeg<>(bm25Leg, FusionEntry::score, bm25Weight));
        }

        List<ScoreFusion.FusedResult<FusionEntry<C>>> fused = switch (query.fusionStrategy()) {
            case RRF -> ScoreFusion.rrf(legs, idExtractor, query.topK(), 60);
            case CC -> ScoreFusion.convexCombination(legs, idExtractor, query.topK());
            case DBSF -> throw new UnsupportedOperationException("DBSF is not supported for CBR — use RRF or CC");
        };

        List<ScoredCbrCase<C>> results = new ArrayList<>(fused.size());
        for (var f : fused) {
            double score = Math.max(-1.0, Math.min(1.0, f.score()));
            if (query.fusionStrategy() == FusionStrategy.RRF || score >= query.minSimilarity()) {
                results.add(new ScoredCbrCase<>(f.item().cbrCase(), f.item().caseId(), score, false, Map.of(), f.item().storedAt(), f.item().scope(), null));
            }
        }
        return Collections.unmodifiableList(results);
    }

    private List<ScoredPoint> executeDenseSearch(String collection, Filter filter,
                                                 CbrQuery query, Embedding queryEmbedding) {
        SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
            .setCollectionName(collection)
            .addAllVector(queryEmbedding.vectorAsList())
            .setVectorName(config.denseVectorName())
            .setFilter(filter)
            .setLimit(query.topK() * config.oversampleFactor())
            .setWithPayload(WithPayloadSelectorFactory.enable(true));
        return awaitFuture(collectionManager.client().searchAsync(searchBuilder.build()), "denseSearch");
    }

    private List<ScoredPoint> executeSpladeSearch(String collection, Filter filter,
                                                   CbrQuery query,
                                                   Map<Integer, Float> sparseEmbedding) {
        List<Float> sparseValues = new ArrayList<>(sparseEmbedding.size());
        List<Integer> sparseIndices = new ArrayList<>(sparseEmbedding.size());
        for (Map.Entry<Integer, Float> entry : sparseEmbedding.entrySet()) {
            sparseIndices.add(entry.getKey());
            sparseValues.add(entry.getValue());
        }

        int limit = config.spladeTopK() > 0 ? config.spladeTopK() : query.topK();
        var queryPoints = io.qdrant.client.grpc.Points.QueryPoints.newBuilder()
            .setCollectionName(collection)
            .setQuery(io.qdrant.client.QueryFactory.nearest(sparseValues, sparseIndices))
            .setUsing(config.spladeVectorName())
            .setFilter(filter)
            .setLimit(limit)
            .setWithPayload(WithPayloadSelectorFactory.enable(true))
            .build();
        return awaitFuture(collectionManager.client().queryAsync(queryPoints), "spladeSearch");
    }

    private List<ScoredPoint> executeBm25Search(String collection, Filter filter,
                                                 CbrQuery query) {
        String expandedQuery = CamelCaseExpander.expand(query.problem());
        int limit = config.bm25TopK() > 0 ? config.bm25TopK() : query.topK();
        var queryPoints = io.qdrant.client.grpc.Points.QueryPoints.newBuilder()
            .setCollectionName(collection)
            .setQuery(io.qdrant.client.QueryFactory.nearest(
                io.qdrant.client.grpc.Points.Document.newBuilder()
                    .setText(expandedQuery)
                    .setModel(config.bm25Model())
                    .build()))
            .setUsing(config.bm25VectorName())
            .setFilter(filter)
            .setLimit(limit)
            .setWithPayload(WithPayloadSelectorFactory.enable(true))
            .build();
        return awaitFuture(collectionManager.client().queryAsync(queryPoints), "bm25Search");
    }

    private List<ScoredPoint> executeFilterQuery(String collection, Filter filter, int limit) {
        var scrollBuilder = io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
            .setCollectionName(collection)
            .setFilter(filter)
            .setLimit(limit)
            .setWithPayload(WithPayloadSelectorFactory.enable(true));

        var response = awaitFuture(
            collectionManager.client().scrollAsync(scrollBuilder.build()), "filterQuery");
        List<ScoredPoint> results = new ArrayList<>(response.getResultCount());
        for (var retrieved : response.getResultList()) {
            results.add(ScoredPoint.newBuilder()
                .setId(retrieved.getId())
                .putAllPayload(retrieved.getPayloadMap())
                .setScore(1.0f)
                .build());
        }
        return results;
    }

    @Override
    public Integer erase(EraseRequest request) {
        int delegateCount = (delegate != null) ? delegate.erase(request) : 0;

        Filter.Builder builder = Filter.newBuilder()
            .addMust(ConditionFactory.matchKeyword("entityId", request.entityId()))
            .addMust(ConditionFactory.matchKeyword("domain", request.domain().name()))
            .addMust(ConditionFactory.matchKeyword("tenantId", request.tenantId()));
        if (request.caseId() != null) {
            builder.addMust(ConditionFactory.matchKeyword("caseId", request.caseId()));
        }
        Filter filter = builder.build();

        int qdrantCount = eraseFromAllCollections(filter);
        return delegate != null ? delegateCount : qdrantCount;
    }

    @Override
    public Integer eraseEntity(String entityId, String tenantId) {
        int delegateCount = (delegate != null) ? delegate.eraseEntity(entityId, tenantId) : 0;

        Filter filter = Filter.newBuilder()
            .addMust(ConditionFactory.matchKeyword("entityId", entityId))
            .addMust(ConditionFactory.matchKeyword("tenantId", tenantId))
            .build();

        int qdrantCount = eraseFromAllCollections(filter);
        return delegate != null ? delegateCount : qdrantCount;
    }

    private int eraseFromAllCollections(Filter filter) {
        if (schemas.isEmpty()) return 0;
        int total = 0;
        for (String caseType : schemas.keySet()) {
            String collection = collectionManager.collectionName(caseType);
            boolean exists = awaitFuture(
                collectionManager.client().collectionExistsAsync(collection), "collectionExists");
            if (exists) {
                total += collectionManager.deleteByFilter(collection, filter);
            }
        }
        return total;
    }

    @Override
    public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {
        java.util.Objects.requireNonNull(scope, "scope required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");

        int total = 0;
        for (String caseType : schemas.keySet()) {
            String collection = collectionManager.collectionName(caseType);
            boolean exists = awaitFuture(
                collectionManager.client().collectionExistsAsync(collection), "collectionExists");
            if (!exists) continue;

            Filter tenantFilter = Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("tenantId", tenantId))
                .build();

            var scrollResult = awaitFuture(collectionManager.client().scrollAsync(
                io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                    .setCollectionName(collection)
                    .setFilter(tenantFilter)
                    .setLimit(100000)
                    .setWithPayload(WithPayloadSelectorFactory.include(
                        List.of("scope", "entityId", "domain", "caseId")))
                    .build()), "scrollForEraseByScope");

            List<PointId> toDelete = new ArrayList<>();
            for (var point : scrollResult.getResultList()) {
                Map<String, Value> payload = point.getPayloadMap();
                io.casehub.platform.api.path.Path storedScope = extractScope(payload);
                if (scope.segments().isEmpty()
                    || storedScope.equals(scope)
                    || scope.isAncestorOf(storedScope)) {
                    toDelete.add(point.getId());
                    if (delegate != null) {
                        String eid = extractString(payload, "entityId");
                        String dom = extractString(payload, "domain");
                        String cid = extractString(payload, "caseId");
                        if (eid != null && dom != null && cid != null) {
                            delegate.erase(new EraseRequest(eid, new MemoryDomain(dom), tenantId, cid));
                        }
                    }
                }
            }

            if (!toDelete.isEmpty()) {
                awaitFuture(collectionManager.client().deleteAsync(collection, toDelete), "deletePoints");
                total += toDelete.size();
            }
        }
        return total;
    }

    @Override
    public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {
        for (String caseType : schemas.keySet()) {
            String collection = collectionManager.collectionName(caseType);
            boolean exists = awaitFuture(
                collectionManager.client().collectionExistsAsync(collection), "collectionExists");
            if (!exists) continue;

            UUID pointUuid = CbrPointBuilder.pointId(tenantId, caseType, caseId);
            var pointId = PointIdFactory.id(pointUuid);
            var points = awaitFuture(
                collectionManager.client().retrieveAsync(collection, List.of(pointId), true, false, null),
                "retrieveForOutcome");
            if (points.isEmpty()) continue;

            var payload = points.getFirst().getPayloadMap();

            String existingLastOutcome = extractString(payload, "last_outcome_at");
            if (existingLastOutcome != null) {
                Instant existingInstant = Instant.parse(existingLastOutcome);
                if (!outcome.observedAt().isAfter(existingInstant)) continue;
            }

            Double oldConfidenceVal = extractDouble(payload, "confidence");
            CbrFeatureSchema schema = schemas.get(caseType);
            double lr = (schema != null && schema.learningRate() != null)
                        ? schema.learningRate() : CbrOutcome.DEFAULT_LEARNING_RATE;
            io.casehub.neocortex.cognitive.Confidence newConf = CbrOutcome.adjustConfidence(
                oldConfidenceVal != null ? io.casehub.neocortex.cognitive.Confidence.unknown(oldConfidenceVal) : null,
                outcome.successRate(), lr);

            Map<String, Value> updates = new HashMap<>();
            updates.put("outcome", ValueFactory.value(outcome.result().name()));
            updates.put("confidence", ValueFactory.value(newConf.value()));
            updates.put("last_outcome_at", ValueFactory.value(outcome.observedAt().toString()));
            if (outcome.detail() != null) {
                updates.put("outcome_detail", ValueFactory.value(outcome.detail()));
            }

            awaitFuture(collectionManager.client().setPayloadAsync(
                collection, updates, (PointId) pointId, null, null, null), "setPayload");
        }
    }

    @Override
    public boolean supersede(String caseId, String tenantId, String supersedingCaseId, String reason) {
        java.util.Objects.requireNonNull(caseId, "caseId required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        for (String caseType : schemas.keySet()) {
            String collection = collectionManager.collectionName(caseType);
            boolean exists = awaitFuture(
                collectionManager.client().collectionExistsAsync(collection), "collectionExists");
            if (!exists) continue;

            UUID pointUuid = CbrPointBuilder.pointId(tenantId, caseType, caseId);
            var pointId = PointIdFactory.id(pointUuid);
            var points = awaitFuture(
                collectionManager.client().retrieveAsync(collection, List.of(pointId), true, false, null),
                "retrieveForSupersede");
            if (points.isEmpty()) continue;

            var payload = points.getFirst().getPayloadMap();
            Value existing = payload.get("_superseded_at");
            if (existing != null && existing.hasIntegerValue() && existing.getIntegerValue() > 0) {
                return false;
            }

            Map<String, Value> updates = new HashMap<>();
            updates.put("_superseded_at", ValueFactory.value(Instant.now().toEpochMilli()));
            if (supersedingCaseId != null) updates.put("_superseding_case_id", ValueFactory.value(supersedingCaseId));
            if (reason != null) updates.put("_supersession_reason", ValueFactory.value(reason));

            awaitFuture(collectionManager.client().setPayloadAsync(
                collection, updates, (PointId) pointId, null, null, null), "setPayload");
            awaitFuture(collectionManager.client().deletePayloadAsync(
                collection, List.of("_reinstated_at"), (PointId) pointId, null, null, null), "deletePayload");
            return true;
        }
        return false;
    }

    @Override
    public boolean reinstate(String caseId, String tenantId) {
        java.util.Objects.requireNonNull(caseId, "caseId required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        for (String caseType : schemas.keySet()) {
            String collection = collectionManager.collectionName(caseType);
            boolean exists = awaitFuture(
                collectionManager.client().collectionExistsAsync(collection), "collectionExists");
            if (!exists) continue;

            UUID pointUuid = CbrPointBuilder.pointId(tenantId, caseType, caseId);
            var pointId = PointIdFactory.id(pointUuid);
            var points = awaitFuture(
                collectionManager.client().retrieveAsync(collection, List.of(pointId), true, false, null),
                "retrieveForReinstate");
            if (points.isEmpty()) continue;

            var payload = points.getFirst().getPayloadMap();
            Value existing = payload.get("_superseded_at");
            if (existing == null || !existing.hasIntegerValue() || existing.getIntegerValue() == 0) {
                return false;
            }

            Map<String, Value> updates = new HashMap<>();
            updates.put("_reinstated_at", ValueFactory.value(Instant.now().toEpochMilli()));
            awaitFuture(collectionManager.client().setPayloadAsync(
                collection, updates, (PointId) pointId, null, null, null), "setPayload");
            awaitFuture(collectionManager.client().deletePayloadAsync(
                collection,
                List.of("_superseded_at", "_superseding_case_id", "_supersession_reason"),
                (PointId) pointId, null, null, null), "deletePayload");
            return true;
        }
        return false;
    }

    @Override
    public List<String> findCaseIds(String tenantId, MemoryDomain domain,
                                     String caseType, Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> filters) {
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        java.util.Objects.requireNonNull(domain, "domain required");
        java.util.Objects.requireNonNull(caseType, "caseType required");
        java.util.Objects.requireNonNull(filters, "filters required");

        CbrFeatureSchema schema = schemas.get(caseType);
        if (!filters.isEmpty()) {
            if (schema == null) {
                throw new IllegalStateException(
                        "Cannot apply filters: no schema registered for caseType '" + caseType + "'");
            }
            CbrFeatureValidator.validateFilters(filters, schema);
        }

        String collection = collectionManager.collectionName(caseType);
        boolean exists = awaitFuture(
                collectionManager.client().collectionExistsAsync(collection), "collectionExists");
        if (!exists) return List.of();

        Filter.Builder builder = Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("tenantId", tenantId))
                .addMust(ConditionFactory.matchKeyword("domain", domain.name()))
                .addMust(ConditionFactory.matchKeyword("caseType", caseType))
                .addMustNot(ConditionFactory.range("_superseded_at",
                        Range.newBuilder().setGt(0).build()));

        Filter filter = CbrQueryTranslator.applyStructuralFilters(builder.build(), filters, schema);

        var scrollResult = awaitFuture(collectionManager.client().scrollAsync(
                io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                        .setCollectionName(collection)
                        .setFilter(filter)
                        .setLimit(10000)
                        .setWithPayload(WithPayloadSelectorFactory.include(List.of("caseId")))
                        .build()), "scrollForFindCaseIds");

        List<String> result = new ArrayList<>();
        for (var point : scrollResult.getResultList()) {
            String caseId = extractString(point.getPayloadMap(), "caseId");
            if (caseId != null) result.add(caseId);
        }
        if (result.size() >= 10000) {
            throw new IllegalStateException("findCaseIds exceeded 10,000 results — narrow filters");
        }
        return result;
    }

    @Override
    public int supersedeMatching(String tenantId, MemoryDomain domain, String caseType,
                                  Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> filters, String reason) {
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        java.util.Objects.requireNonNull(domain, "domain required");
        java.util.Objects.requireNonNull(caseType, "caseType required");
        java.util.Objects.requireNonNull(filters, "filters required");

        CbrFeatureSchema schema = schemas.get(caseType);
        if (!filters.isEmpty()) {
            if (schema == null) {
                throw new IllegalStateException(
                        "Cannot apply filters: no schema registered for caseType '" + caseType + "'");
            }
            CbrFeatureValidator.validateFilters(filters, schema);
        }

        String collection = collectionManager.collectionName(caseType);
        boolean exists = awaitFuture(
                collectionManager.client().collectionExistsAsync(collection), "collectionExists");
        if (!exists) return 0;

        Filter.Builder builder = Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("tenantId", tenantId))
                .addMust(ConditionFactory.matchKeyword("domain", domain.name()))
                .addMust(ConditionFactory.matchKeyword("caseType", caseType))
                .addMustNot(ConditionFactory.range("_superseded_at",
                        Range.newBuilder().setGt(0).build()));

        Filter filter = CbrQueryTranslator.applyStructuralFilters(builder.build(), filters, schema);

        var scrollResult = awaitFuture(collectionManager.client().scrollAsync(
                io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                        .setCollectionName(collection)
                        .setFilter(filter)
                        .setLimit(10000)
                        .setWithPayload(WithPayloadSelectorFactory.include(List.of("caseId")))
                        .build()), "scrollForSupersedeMatching");

        int count = 0;
        Instant now = Instant.now();
        for (var point : scrollResult.getResultList()) {
            Map<String, Value> updates = new HashMap<>();
            updates.put("_superseded_at", ValueFactory.value(now.toEpochMilli()));
            if (reason != null) updates.put("_supersession_reason", ValueFactory.value(reason));

            awaitFuture(collectionManager.client().setPayloadAsync(
                    collection, updates, (PointId) point.getId(), null, null, null), "setPayload");
            awaitFuture(collectionManager.client().deletePayloadAsync(
                    collection, List.of("_reinstated_at"),
                    (PointId) point.getId(), null, null, null), "deletePayload");
            count++;
        }
        return count;
    }

    @Override
    public int supersedeAll(java.util.Collection<String> caseIds, String tenantId, String reason) {
        java.util.Objects.requireNonNull(caseIds, "caseIds required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        int count = 0;
        for (String caseId : caseIds) {
            if (supersede(caseId, tenantId, null, reason)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int reinstateMatching(String tenantId, MemoryDomain domain, String caseType,
                                  Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> filters) {
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        java.util.Objects.requireNonNull(domain, "domain required");
        java.util.Objects.requireNonNull(caseType, "caseType required");
        java.util.Objects.requireNonNull(filters, "filters required");

        CbrFeatureSchema schema = schemas.get(caseType);
        if (!filters.isEmpty()) {
            if (schema == null) {
                throw new IllegalStateException(
                        "Cannot apply filters: no schema registered for caseType '" + caseType + "'");
            }
            CbrFeatureValidator.validateFilters(filters, schema);
        }

        String collection = collectionManager.collectionName(caseType);
        boolean exists = awaitFuture(
                collectionManager.client().collectionExistsAsync(collection), "collectionExists");
        if (!exists) return 0;

        Filter.Builder builder = Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("tenantId", tenantId))
                .addMust(ConditionFactory.matchKeyword("domain", domain.name()))
                .addMust(ConditionFactory.matchKeyword("caseType", caseType))
                .addMust(ConditionFactory.range("_superseded_at",
                        Range.newBuilder().setGt(0).build()));

        Filter filter = CbrQueryTranslator.applyStructuralFilters(builder.build(), filters, schema);

        var scrollResult = awaitFuture(collectionManager.client().scrollAsync(
                io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                        .setCollectionName(collection)
                        .setFilter(filter)
                        .setLimit(10000)
                        .setWithPayload(WithPayloadSelectorFactory.include(List.of("caseId")))
                        .build()), "scrollForReinstateMatching");

        int count = 0;
        Instant now = Instant.now();
        for (var point : scrollResult.getResultList()) {
            Map<String, Value> updates = new HashMap<>();
            updates.put("_reinstated_at", ValueFactory.value(now.toEpochMilli()));
            awaitFuture(collectionManager.client().setPayloadAsync(
                    collection, updates, (PointId) point.getId(), null, null, null), "setPayload");
            awaitFuture(collectionManager.client().deletePayloadAsync(
                    collection, List.of("_superseded_at", "_superseding_case_id", "_supersession_reason"),
                    (PointId) point.getId(), null, null, null), "deletePayload");
            count++;
        }
        return count;
    }

    @Override
    public int reinstateAll(java.util.Collection<String> caseIds, String tenantId) {
        java.util.Objects.requireNonNull(caseIds, "caseIds required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        int count = 0;
        for (String caseId : caseIds) {
            if (reinstate(caseId, tenantId)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Integer purge(CbrRetentionPolicy policy) {
        List<String> targetTypes = policy.caseType() != null
            ? List.of(policy.caseType())
            : List.copyOf(schemas.keySet());

        int total = 0;
        for (String caseType : targetTypes) {
            String collection = collectionManager.collectionName(caseType);
            boolean exists = awaitFuture(
                collectionManager.client().collectionExistsAsync(collection), "collectionExists");
            if (exists) {
                total += purgeCollection(collection, policy);
            }
        }
        return total;
    }

    private int purgeCollection(String collection, CbrRetentionPolicy policy) {
        int ageCount = 0;
        if (policy.maxAgeDays() != null) {
            long cutoffMillis = Instant.now().minus(java.time.Duration.ofDays(policy.maxAgeDays())).toEpochMilli();
            Filter ageFilter = Filter.newBuilder()
                                     .addMust(ConditionFactory.matchKeyword("tenantId", policy.tenantId()))
                                     .addMust(ConditionFactory.matchKeyword("domain", policy.domain().name()))
                                     .addMust(ConditionFactory.range("_stored_at",
                                                                     io.qdrant.client.grpc.Common.Range.newBuilder().setLt(cutoffMillis).build()))
                                     .build();
            ageCount = collectionManager.deleteByFilter(collection, ageFilter);
        }

        int countCount = 0;
        if (policy.maxCasesPerType() != null) {
            Filter scopeFilter = Filter.newBuilder()
                                       .addMust(ConditionFactory.matchKeyword("tenantId", policy.tenantId()))
                                       .addMust(ConditionFactory.matchKeyword("domain", policy.domain().name()))
                                       .build();
            var scrollResult = awaitFuture(collectionManager.client().scrollAsync(
                    io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                                                             .setCollectionName(collection)
                                                             .setWithPayload(WithPayloadSelectorFactory.include(List.of("_stored_at")))
                                                             .setFilter(scopeFilter)
                                                             .setLimit(100000)
                                                             .build()), "scrollForPurge");

            var points = new ArrayList<>(scrollResult.getResultList());
            if (points.size() > policy.maxCasesPerType()) {
                points.sort((a, b) -> {
                    long aTime = a.getPayloadOrDefault("_stored_at", ValueFactory.value(0L)).getIntegerValue();
                    long bTime = b.getPayloadOrDefault("_stored_at", ValueFactory.value(0L)).getIntegerValue();
                    return Long.compare(bTime, aTime);
                });
                List<PointId> toDelete = new ArrayList<>();
                for (int i = policy.maxCasesPerType(); i < points.size(); i++) {
                    toDelete.add(points.get(i).getId());
                }
                awaitFuture(collectionManager.client().deleteAsync(collection, toDelete), "deleteExcessPoints");
                countCount = toDelete.size();
            }
        }

        int trustCount = 0;
        if (policy.minTrustScore() != null) {
            Filter trustFilter = Filter.newBuilder()
                                       .addMust(ConditionFactory.matchKeyword("tenantId", policy.tenantId()))
                                       .addMust(ConditionFactory.matchKeyword("domain", policy.domain().name()))
                                       .addMust(ConditionFactory.range("trust_score",
                                                                       io.qdrant.client.grpc.Common.Range.newBuilder().setLt(policy.minTrustScore()).build()))
                                       .build();
            trustCount = collectionManager.deleteByFilter(collection, trustFilter);
        }

        return ageCount + countCount + trustCount;
    }

    @Override
    public SupersessionStatus getSupersessionStatus(String caseId, String tenantId) {
        java.util.Objects.requireNonNull(caseId, "caseId required");
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        for (String caseType : schemas.keySet()) {
            String collection = collectionManager.collectionName(caseType);
            boolean exists = awaitFuture(
                collectionManager.client().collectionExistsAsync(collection), "collectionExists");
            if (!exists) continue;

            UUID pointUuid = CbrPointBuilder.pointId(tenantId, caseType, caseId);
            var pointId = PointIdFactory.id(pointUuid);
            var points = awaitFuture(
                collectionManager.client().retrieveAsync(collection, List.of(pointId), true, false, null),
                "retrieveForSupersessionStatus");
            if (points.isEmpty()) continue;

            return buildSupersessionStatus(caseId, points.getFirst().getPayloadMap());
        }
        return SupersessionStatus.NOT_SUPERSEDED;
    }

    @Override
    public List<SupersessionStatus> findSupersededCases(String tenantId, MemoryDomain domain) {
        java.util.Objects.requireNonNull(tenantId, "tenantId required");
        java.util.Objects.requireNonNull(domain, "domain required");
        List<SupersessionStatus> allResults = new ArrayList<>();
        for (String caseType : schemas.keySet()) {
            String collection = collectionManager.collectionName(caseType);
            boolean exists = awaitFuture(
                collectionManager.client().collectionExistsAsync(collection), "collectionExists");
            if (!exists) continue;

            Filter domainFilter = Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("tenantId", tenantId))
                .addMust(ConditionFactory.matchKeyword("domain", domain.name()))
                .build();
            var scrollResult = awaitFuture(collectionManager.client().scrollAsync(
                io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                    .setCollectionName(collection)
                    .setFilter(domainFilter)
                    .setLimit(10000)
                    .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build()), "scrollForSuperseded");

            for (var p : scrollResult.getResultList()) {
                Value v = p.getPayloadMap().get("_superseded_at");
                if (v != null && v.hasIntegerValue() && v.getIntegerValue() > 0) {
                    allResults.add(buildSupersessionStatus(
                        extractString(p.getPayloadMap(), "caseId"), p.getPayloadMap()));
                }
            }
        }
        return allResults;
    }

    @Override
    public java.util.Set<String> discoverTenants(io.casehub.neocortex.memory.MemoryDomain domain) {
        java.util.Set<String> tenants = new java.util.LinkedHashSet<>();
        for (String caseType : schemas.keySet()) {
            String collection = collectionManager.collectionName(caseType);
            boolean exists = awaitFuture(
                    collectionManager.client().collectionExistsAsync(collection), "collectionExists");
            if (!exists) {continue;}

            Filter domainFilter = Filter.newBuilder()
                                        .addMust(ConditionFactory.matchKeyword("domain", domain.name()))
                                        .build();
            var scrollResult = awaitFuture(collectionManager.client().scrollAsync(
                    io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                                                             .setCollectionName(collection)
                                                             .setFilter(domainFilter)
                                                             .setWithPayload(WithPayloadSelectorFactory.include(List.of("tenantId")))
                                                             .setLimit(100000)
                                                             .build()), "scrollForDiscoverTenants");
            for (var point : scrollResult.getResultList()) {
                var tenantVal = point.getPayloadOrDefault("tenantId", null);
                if (tenantVal != null && tenantVal.hasStringValue()) {
                    tenants.add(tenantVal.getStringValue());
                }
            }
        }
        return java.util.Collections.unmodifiableSet(tenants);
    }

    @Override
    public io.casehub.neocortex.memory.cbr.CbrScanResult scan(io.casehub.neocortex.memory.cbr.CbrScanRequest request) {
        String collection = collectionManager.collectionName(request.caseType());
        boolean exists = awaitFuture(
                collectionManager.client().collectionExistsAsync(collection), "collectionExists");
        if (!exists) {return new io.casehub.neocortex.memory.cbr.CbrScanResult(List.of(), null);}

        Filter scopeFilter = Filter.newBuilder()
                                   .addMust(ConditionFactory.matchKeyword("tenantId", request.tenantId()))
                                   .addMust(ConditionFactory.matchKeyword("domain", request.domain().name()))
                                   .build();

        var scrollBuilder = io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                                                                     .setCollectionName(collection)
                                                                     .setFilter(scopeFilter)
                                                                     .setWithPayload(WithPayloadSelectorFactory.include(
                                                                             List.of("caseId", "entityId", "caseType", "producer_agent_id", "trust_score", "_stored_at")))
                                                                     .setLimit(request.limit());
        if (request.cursor() != null) {
            scrollBuilder.setOffset(PointIdFactory.id(UUID.fromString(request.cursor())));
        }

        var scrollResult = awaitFuture(collectionManager.client().scrollAsync(scrollBuilder.build()), "scrollForScan");

        List<io.casehub.neocortex.memory.cbr.CbrCaseSummary> result = new java.util.ArrayList<>();
        for (var point : scrollResult.getResultList()) {
            var    payload         = point.getPayloadMap();
            String caseId          = payload.containsKey("caseId") ? payload.get("caseId").getStringValue() : null;
            String entityId        = payload.containsKey("entityId") ? payload.get("entityId").getStringValue() : null;
            String caseType        = payload.containsKey("caseType") ? payload.get("caseType").getStringValue() : request.caseType();
            String producerAgentId = payload.containsKey("producer_agent_id") ? payload.get("producer_agent_id").getStringValue() : null;
            Double trustScore = payload.containsKey("trust_score") && payload.get("trust_score").hasDoubleValue()
                                ? payload.get("trust_score").getDoubleValue() : null;
            Instant storedAt = payload.containsKey("_stored_at") && payload.get("_stored_at").hasIntegerValue()
                               ? Instant.ofEpochMilli(payload.get("_stored_at").getIntegerValue()) : null;
            result.add(new io.casehub.neocortex.memory.cbr.CbrCaseSummary(caseId, entityId, caseType, producerAgentId, trustScore, storedAt));
        }
        String nextCursor = result.size() >= request.limit() && scrollResult.hasNextPageOffset()
                            ? scrollResult.getNextPageOffset().getUuid()
                            : null;
        return new io.casehub.neocortex.memory.cbr.CbrScanResult(List.copyOf(result), nextCursor);
    }


    private SupersessionStatus buildSupersessionStatus(String caseId, Map<String, Value> payload) {
        Value supersededAtVal = payload.get("_superseded_at");
        Value reinstatedAtVal = payload.get("_reinstated_at");
        boolean superseded = supersededAtVal != null && supersededAtVal.hasIntegerValue() && supersededAtVal.getIntegerValue() > 0;
        Instant supersededAt = superseded ? Instant.ofEpochMilli(supersededAtVal.getIntegerValue()) : null;
        Instant reinstatedAt = reinstatedAtVal != null && reinstatedAtVal.hasIntegerValue() && reinstatedAtVal.getIntegerValue() > 0
                               ? Instant.ofEpochMilli(reinstatedAtVal.getIntegerValue()) : null;
        String supersedingCaseId = superseded ? extractString(payload, "_superseding_case_id") : null;
        String reason = superseded ? extractString(payload, "_supersession_reason") : null;
        return new SupersessionStatus(caseId, superseded, supersededAt, supersedingCaseId, reason, reinstatedAt);
    }

    private int vectorDimension() {
        return embeddingModel != null ? embeddingModel.dimension() : 0;
    }

    private Map<String, LocalSimilarityFunction> buildTextOverrides(
            CbrFeatureSchema schema, EmbeddingTextSimilarity textSim) {
        if (textSim == null) return Map.of();
        Map<String, LocalSimilarityFunction> overrides = new HashMap<>();
        for (FeatureField field : schema.fields()) {
            if (field instanceof FeatureField.Text t && t.semantic()) {
                overrides.put(field.name(), textSim);
            }
        }
        return overrides.isEmpty() ? Map.of() : Collections.unmodifiableMap(overrides);
    }

    private <C extends CbrCase> List<String> collectSemanticTextValues(
            CbrQuery query, List<ReconstructedCandidate<C>> candidates, Set<String> fieldNames) {
        List<String> texts = new ArrayList<>();
        for (String fieldName : fieldNames) {
            if (query.features().get(fieldName) instanceof FeatureValue.StringVal s) texts.add(s.value());
            for (var rc : candidates) {
                if (rc.cbrCase().features().get(fieldName) instanceof FeatureValue.StringVal s) texts.add(s.value());
            }
        }
        return texts;
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> void mergePoints(List<ScoredPoint> points,
                                                 Map<String, ReconstructedCandidate<C>> map,
                                                 Class<C> caseClass, boolean useDenseScore) {
        for (ScoredPoint point : points) {
            String pointId = point.getId().getUuid();
            if (map.containsKey(pointId)) continue;
            try {
                C cbrCase = (C) reconstructCase(point.getPayloadMap(), caseClass);
                if (cbrCase != null) {
                    String caseId = extractString(point.getPayloadMap(), "caseId");
                    Instant storedAt = extractStoredAt(point.getPayloadMap());
                    map.put(pointId, new ReconstructedCandidate<>(pointId, cbrCase,
                        useDenseScore ? point.getScore() : 0f, caseId, storedAt,
                        extractScope(point.getPayloadMap())));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to reconstruct case from point", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <C extends CbrCase> List<ReconstructedCandidate<C>> reconstructAll(
            List<ScoredPoint> scoredPoints, Class<C> caseClass) {
        List<ReconstructedCandidate<C>> result = new ArrayList<>(scoredPoints.size());
        for (ScoredPoint point : scoredPoints) {
            try {
                C cbrCase = (C) reconstructCase(point.getPayloadMap(), caseClass);
                if (cbrCase != null) {
                    result.add(new ReconstructedCandidate<>(
                        point.getId().getUuid(), cbrCase, point.getScore(),
                        extractString(point.getPayloadMap(), "caseId"),
                        extractStoredAt(point.getPayloadMap()),
                        extractScope(point.getPayloadMap())));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to reconstruct case from point", e);
            }
        }
        return result;
    }

    private <C extends CbrCase> List<ScoredCbrCase<C>> trimToTopK(
            List<ScoredCbrCase<C>> candidates, int topK) {
        if (candidates.size() <= topK) return Collections.unmodifiableList(candidates);
        return Collections.unmodifiableList(candidates.subList(0, topK));
    }

    private <C extends CbrCase> C reconstructCase(Map<String, Value> payload, Class<C> caseClass) {
        String problem = extractString(payload, "problem");
        String solution = extractString(payload, "solution");
        if (problem == null || solution == null) return null;

        String outcome = extractString(payload, "outcome");
        Double confidenceVal = extractDouble(payload, "confidence");
        io.casehub.neocortex.cognitive.Confidence confidence = confidenceVal != null
                ? io.casehub.neocortex.cognitive.Confidence.unknown(confidenceVal) : null;
        Double trustScore = extractDouble(payload, "trust_score");
        String producerAgentId = extractString(payload, "producer_agent_id");
        String cbrType = extractString(payload, "_cbr_type");
        CbrCase reconstructed = switch (cbrType) {
            case FeatureVectorCbrCase.CBR_TYPE -> reconstructFeatureVector(payload, problem, solution, outcome, confidence, trustScore, producerAgentId);
            case PlanCbrCase.CBR_TYPE -> reconstructPlanCase(payload, problem, solution, outcome, confidence, trustScore, producerAgentId);
            case TextualCbrCase.CBR_TYPE -> new TextualCbrCase(problem, solution, outcome, confidence, trustScore, producerAgentId);
            case null -> throw new IllegalStateException("Missing _cbr_type in CBR point");
            default -> throw new IllegalArgumentException("Unknown CBR type: " + cbrType);
        };

        if (!caseClass.isInstance(reconstructed)) return null;
        return caseClass.cast(reconstructed);
    }

    private CbrCase reconstructPlanCase(Map<String, Value> payload,
                                        String problem, String solution,
                                        String outcome, io.casehub.neocortex.cognitive.Confidence confidence,
                                        Double trustScore, String producerAgentId) {
        Map<String, FeatureValue> features = Map.of();
        String featuresJson = extractString(payload, "_features_json");
        if (featuresJson != null) {
            try {
                features = CbrPointBuilder.fromRawMap(MAPPER.readValue(featuresJson, MAP_TYPE));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Corrupted _features_json in CBR point", e);
            }
        }

        List<PlanTrace> planTrace = List.of();
        String planTraceJson = extractString(payload, "_plan_trace_json");
        if (planTraceJson != null) {
            try {
                planTrace = MAPPER.readValue(planTraceJson, PLAN_TRACE_TYPE);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Corrupted _plan_trace_json in CBR point", e);
            }
        }

        return new PlanCbrCase(problem, solution, outcome, confidence, features, planTrace, trustScore, producerAgentId);
    }

    private CbrCase reconstructFeatureVector(Map<String, Value> payload,
                                             String problem, String solution,
                                             String outcome, io.casehub.neocortex.cognitive.Confidence confidence,
                                             Double trustScore, String producerAgentId) {
        String featuresJson = extractString(payload, "_features_json");
        if (featuresJson == null) {
            return new FeatureVectorCbrCase(problem, solution, outcome, confidence, Map.of(), trustScore, producerAgentId);
        }
        try {
            var features = CbrPointBuilder.fromRawMap(MAPPER.readValue(featuresJson, MAP_TYPE));
            return new FeatureVectorCbrCase(problem, solution, outcome, confidence, features, trustScore, producerAgentId);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Corrupted _features_json in CBR point", e);
        }
    }

    private record ReconstructedCandidate<C extends CbrCase>(
        String pointId, C cbrCase, float vectorScore,
        String caseId, Instant storedAt,
        io.casehub.platform.api.path.Path scope) {}
}
