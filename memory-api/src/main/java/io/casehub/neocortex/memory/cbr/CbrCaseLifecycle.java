package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface CbrCaseLifecycle {
    boolean supersede(String caseId, String tenantId, String supersedingCaseId, String reason);
    boolean reinstate(String caseId, String tenantId);
    SupersessionStatus getSupersessionStatus(String caseId, String tenantId);
    List<SupersessionStatus> findSupersededCases(String tenantId, MemoryDomain domain);
    void recordOutcome(String caseId, String tenantId, CbrOutcome outcome);

    int supersedeMatching(String tenantId, MemoryDomain domain, String caseType,
                          Map<String, CbrFilter> filters, String reason);
    int supersedeAll(Collection<String> caseIds, String tenantId, String reason);
    int reinstateMatching(String tenantId, MemoryDomain domain, String caseType,
                          Map<String, CbrFilter> filters);
    int reinstateAll(Collection<String> caseIds, String tenantId);
}
