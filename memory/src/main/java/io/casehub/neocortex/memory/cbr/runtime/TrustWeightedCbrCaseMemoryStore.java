package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.cbr.*;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.*;

@Decorator
@Priority(60)
@IfBuildProperty(name = "casehub.cbr.trust-weighting.enabled", stringValue = "true")
public class TrustWeightedCbrCaseMemoryStore extends DelegatingCbrCaseMemoryStore {

    private final TrustWeightingFunction weightingFunction;
    private final AgentTrustProvider trustProvider;

    @Inject
    TrustWeightedCbrCaseMemoryStore(@Delegate @Any CbrCaseMemoryStore delegate,
                                     TrustWeightingFunction weightingFunction,
                                     Instance<AgentTrustProvider> trustProviderInstance) {
        this(delegate, weightingFunction,
             trustProviderInstance.isResolvable() ? trustProviderInstance.get() : null);
    }

    TrustWeightedCbrCaseMemoryStore(CbrCaseMemoryStore delegate,
                                     TrustWeightingFunction weightingFunction,
                                     AgentTrustProvider trustProvider) {
        super(delegate);
        this.weightingFunction = weightingFunction;
        this.trustProvider = trustProvider;
    }

    @Override
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
            CbrQuery query, Class<C> caseClass) {
        List<ScoredCbrCase<C>> results = delegate.retrieveSimilar(query, caseClass);
        if (results.isEmpty()) return results;

        Map<String, OptionalDouble> trajectoryCache = new HashMap<>();
        List<ScoredCbrCase<C>> weighted = new ArrayList<>(results.size());
        for (ScoredCbrCase<C> scored : results) {
            Double trust = scored.cbrCase().trustScore();
            if (trust == null) {
                weighted.add(scored);
                continue;
            }
            OptionalDouble trajectory = computeTrajectory(scored.cbrCase(), trajectoryCache);
            double newScore = weightingFunction.apply(scored.score(), trust, trajectory);
            Double delta = trajectory.isPresent() ? trajectory.getAsDouble() : null;
            weighted.add(scored.withScore(newScore).withTrustTrajectory(delta));
        }
        weighted.sort((a, b) -> Double.compare(b.score(), a.score()));
        return Collections.unmodifiableList(weighted);
    }

    private OptionalDouble computeTrajectory(CbrCase cbrCase,
                                              Map<String, OptionalDouble> cache) {
        if (trustProvider == null || cbrCase.producerAgentId() == null
                || cbrCase.trustScore() == null) {
            return OptionalDouble.empty();
        }
        String agentId = cbrCase.producerAgentId();
        OptionalDouble current = cache.computeIfAbsent(agentId,
                id -> trustProvider.currentTrustScore(id));
        if (current.isEmpty()) return OptionalDouble.empty();
        return OptionalDouble.of(current.getAsDouble() - cbrCase.trustScore());
    }

}
