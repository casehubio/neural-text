package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import java.util.List;

public interface CbrCaseLifecycle {
    void supersede(String caseId, String tenantId, String supersedingCaseId, String reason);
    void reinstate(String caseId, String tenantId);
    SupersessionStatus getSupersessionStatus(String caseId, String tenantId);
    List<SupersessionStatus> findSupersededCases(String tenantId, MemoryDomain domain);
    void recordOutcome(String caseId, String tenantId, CbrOutcome outcome);
}
