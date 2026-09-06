package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.SupersessionStatus;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.neocortex.memory.cbr.CbrFilter;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@DefaultBean
@ApplicationScoped
public class NoOpCbrCaseMemoryStore implements CbrCaseMemoryStore {

    @Override
    public void registerSchema(CbrFeatureSchema schema) {}

    @Override
    public String store(CbrCase cbrCase, String caseType, String entityId, MemoryDomain domain,
                        String tenantId, String caseId, io.casehub.platform.api.path.Path scope) {
        return "";
    }

    @Override
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery query, Class<C> caseClass) {
        return List.of();
    }

    @Override
    public Integer erase(EraseRequest request) {
        return 0;
    }

    @Override
    public Integer eraseEntity(String entityId, String tenantId) {
        return 0;
    }

    @Override
    public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {
        return 0;
    }


    @Override
    public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {}

    @Override
    public Integer purge(CbrRetentionPolicy policy) {
        return 0;
    }

    @Override
    public boolean supersede(String caseId, String tenantId, String supersedingCaseId, String reason) {
        return false;
    }

    @Override
    public boolean reinstate(String caseId, String tenantId) {
        return false;
    }

    @Override
    public SupersessionStatus getSupersessionStatus(String caseId, String tenantId) {
        return SupersessionStatus.NOT_SUPERSEDED;
    }

    @Override
    public java.util.List<SupersessionStatus> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) {
        return java.util.List.of();
    }

    @Override
    public List<String> findCaseIds(String tenantId, MemoryDomain domain,
                                     String caseType, Map<String, CbrFilter> filters) {
        return List.of();
    }

    @Override
    public int supersedeMatching(String tenantId, MemoryDomain domain, String caseType,
                                  Map<String, CbrFilter> filters, String reason) {
        return 0;
    }

    @Override
    public int supersedeAll(Collection<String> caseIds, String tenantId, String reason) {
        return 0;
    }

    @Override
    public int reinstateMatching(String tenantId, MemoryDomain domain, String caseType,
                                  Map<String, CbrFilter> filters) {
        return 0;
    }

    @Override
    public int reinstateAll(Collection<String> caseIds, String tenantId) {
        return 0;
    }

}
