package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.MemoryDomain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface CbrCasesReinstated {
    String tenantId();

    int reinstatedCount();

    Instant reinstatedAt();

    record ByCase(String tenantId, String caseId,
                  Instant reinstatedAt) implements CbrCasesReinstated {
        public ByCase {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(reinstatedAt, "reinstatedAt");
        }

        @Override
        public int reinstatedCount() {
            return 1;
        }
    }

    record ByFilter(String tenantId, MemoryDomain domain, String caseType,
                    Map<String, CbrFilter> filters,
                    int reinstatedCount, Instant reinstatedAt) implements CbrCasesReinstated {
        public ByFilter {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(caseType, "caseType");
            Objects.requireNonNull(filters, "filters");
            Objects.requireNonNull(reinstatedAt, "reinstatedAt");
            filters = Map.copyOf(filters);
        }
    }

    record ByIds(String tenantId, Collection<String> caseIds,
                 int reinstatedCount, Instant reinstatedAt) implements CbrCasesReinstated {
        public ByIds {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(caseIds, "caseIds");
            Objects.requireNonNull(reinstatedAt, "reinstatedAt");
            caseIds = List.copyOf(caseIds);
        }
    }
}
