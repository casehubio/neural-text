package io.casehub.neocortex.memory.cbr.tracking;

import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.DelegatingCbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetrievalRecorded;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTrace;
import io.casehub.neocortex.memory.cbr.CbrRetrievalTracker;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.function.Consumer;

@Decorator
@Priority(50)
@IfBuildProperty(name = "casehub.cbr.tracking.enabled", stringValue = "true")
public class TrackingCbrCaseMemoryStore extends DelegatingCbrCaseMemoryStore {

    private static final Logger LOG = Logger.getLogger(TrackingCbrCaseMemoryStore.class);

    private final CbrRetrievalTracker tracker;
    private final Consumer<CbrRetrievalRecorded> eventSink;

    @Inject
    TrackingCbrCaseMemoryStore(@Delegate @Any CbrCaseMemoryStore delegate,
                                CbrRetrievalTracker tracker,
                                Event<CbrRetrievalRecorded> recordedEvent) {
        this(delegate, tracker, recordedEvent::fire);
    }

    TrackingCbrCaseMemoryStore(CbrCaseMemoryStore delegate,
                                CbrRetrievalTracker tracker,
                                Consumer<CbrRetrievalRecorded> eventSink) {
        super(delegate);
        this.tracker = tracker;
        this.eventSink = eventSink;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
            CbrQuery query, Class<C> caseClass) {
        List<ScoredCbrCase<C>> results = delegate.retrieveSimilar(query, caseClass);
        try {
            String traceId = tracker.record(query, (List<ScoredCbrCase<?>>) (List<?>) results);
            var traced = results.stream()
                    .map(s -> new CbrRetrievalTrace.TracedCase(
                            s.caseId(), s.score(), s.reranked(),
                            s.featureSimilarities(), s.cbrCase().confidence(),
                            s.cbrCase().trustScore(), s.cbrCase().producerAgentId(),
                            toTrajectoryLabel(s.trustTrajectory())))
                    .toList();
            eventSink.accept(new CbrRetrievalRecorded(traceId, query, traced));
        } catch (Exception e) {
            LOG.warn("CBR retrieval tracking failed — returning results unchanged", e);
        }
        return results;
    }

    private static String toTrajectoryLabel(Double delta) {
        if (delta == null) {return null;}
        if (delta < 0) {return "declining";}
        if (delta > 0) {return "improving";}
        return "stable";
    }


}
