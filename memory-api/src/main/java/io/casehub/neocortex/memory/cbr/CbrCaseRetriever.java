package io.casehub.neocortex.memory.cbr;

import java.util.List;

public interface CbrCaseRetriever {
    <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> caseType);

    default CbrScanResult scan(CbrScanRequest request) {
        throw new UnsupportedOperationException(
                "scan not supported by " + getClass().getSimpleName());
    }
}
