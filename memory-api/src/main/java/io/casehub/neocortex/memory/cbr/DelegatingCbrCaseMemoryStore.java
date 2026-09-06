package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.platform.api.path.Path;
import java.util.List;
import java.util.Set;

public abstract class DelegatingCbrCaseMemoryStore implements CbrCaseMemoryStore {

    protected final CbrCaseMemoryStore delegate;

    protected DelegatingCbrCaseMemoryStore(CbrCaseMemoryStore delegate) {
        this.delegate = delegate;
    }

    @Override public void registerSchema(CbrFeatureSchema schema) { delegate.registerSchema(schema); }
    @Override public String store(CbrCase c, String ct, String e, MemoryDomain d, String t, String ci, Path scope) { return delegate.store(c, ct, e, d, t, ci, scope); }
    @Override public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery q, Class<C> ct) { return delegate.retrieveSimilar(q, ct); }
    @Override public Integer erase(EraseRequest request) { return delegate.erase(request); }
    @Override public Integer eraseEntity(String entityId, String tenantId) { return delegate.eraseEntity(entityId, tenantId); }
    @Override public Integer eraseByScope(Path scope, String tenantId) { return delegate.eraseByScope(scope, tenantId); }
    @Override public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) { delegate.recordOutcome(caseId, tenantId, outcome); }
    @Override public Integer purge(CbrRetentionPolicy policy) { return delegate.purge(policy); }
    @Override public void supersede(String caseId, String tenantId, String supersedingCaseId, String reason) { delegate.supersede(caseId, tenantId, supersedingCaseId, reason); }
    @Override public void reinstate(String caseId, String tenantId) { delegate.reinstate(caseId, tenantId); }
    @Override public SupersessionStatus getSupersessionStatus(String caseId, String tenantId) { return delegate.getSupersessionStatus(caseId, tenantId); }
    @Override public List<SupersessionStatus> findSupersededCases(String tenantId, MemoryDomain domain) { return delegate.findSupersededCases(tenantId, domain); }
    @Override public Set<String> discoverTenants(MemoryDomain domain) { return delegate.discoverTenants(domain); }
    @Override public CbrScanResult scan(CbrScanRequest request) { return delegate.scan(request); }
}
