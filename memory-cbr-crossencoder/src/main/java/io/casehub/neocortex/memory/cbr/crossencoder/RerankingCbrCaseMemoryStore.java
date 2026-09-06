package io.casehub.neocortex.memory.cbr.crossencoder;

import io.casehub.neocortex.inference.tasks.CrossEncoderReranker;
import io.casehub.neocortex.inference.tasks.RankedResult;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.DelegatingCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Decorator
@Priority(75)
@Unremovable
@IfBuildProperty(name = "casehub.cbr.reranking.enabled", stringValue = "true")
public class RerankingCbrCaseMemoryStore extends DelegatingCbrCaseMemoryStore {

    private final CrossEncoderReranker reranker;
    private final CbrRerankingConfig config;

    @Inject
    RerankingCbrCaseMemoryStore(@Delegate @Any CbrCaseMemoryStore delegate,
                                 Instance<CrossEncoderReranker> rerankerInstance,
                                 CbrRerankingConfig config) {
        super(delegate);
        this.reranker = rerankerInstance.isResolvable() ? rerankerInstance.get() : null;
        this.config = config;
    }

    RerankingCbrCaseMemoryStore(CbrCaseMemoryStore delegate,
                                 CrossEncoderReranker reranker,
                                 CbrRerankingConfig config) {
        super(delegate);
        this.reranker = reranker;
        this.config = config;
    }

    @Override
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
            CbrQuery query, Class<C> caseClass) {
        if (shouldSkip(query)) {
            return delegate.retrieveSimilar(query, caseClass);
        }

        int fetchSize = Math.max(query.topK(), config.rerankPoolSize());
        CbrQuery overfetchQuery = new CbrQuery(
                query.tenantId(), query.domain(), query.caseType(),
                query.features(), query.filters(), query.weights(), fetchSize,
                query.minSimilarity(), query.notBefore(), query.problem(),
                query.vectorWeight(), query.retrievalMode(), query.fusionStrategy(), query.temporalDecay(),
                query.scope(), query.scopeDecay());

        List<ScoredCbrCase<C>> candidates = delegate.retrieveSimilar(overfetchQuery, caseClass);
        if (candidates.isEmpty()) {return candidates;}

        if (candidates.stream().allMatch(ScoredCbrCase::reranked)) {
            int limit = Math.min(candidates.size(), query.topK());
            return Collections.unmodifiableList(new ArrayList<>(candidates.subList(0, limit)));
        }

        List<String> problemTexts = candidates.stream()
                                              .map(c -> c.cbrCase().problem() != null ? c.cbrCase().problem() : "")
                                              .toList();

        List<RankedResult> ranked = reranker.rerank(query.problem(), problemTexts);

        List<ScoredCbrCase<C>> results = new ArrayList<>(
                Math.min(ranked.size(), query.topK()));
        for (int i = 0; i < Math.min(ranked.size(), query.topK()); i++) {
            RankedResult     r            = ranked.get(i);
            ScoredCbrCase<C> original     = candidates.get(r.originalIndex());
            double           sigmoidScore = 1.0 / (1.0 + Math.exp(-r.score()));
            results.add(original.withScore(sigmoidScore).withReranked());
        }

        return Collections.unmodifiableList(results);}

    private boolean shouldSkip(CbrQuery query) {
        if (reranker == null) return true;
        if (query.retrievalMode() == RetrievalMode.FEATURE_ONLY) return true;
        return query.problem() == null;
    }

}
