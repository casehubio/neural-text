package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.MemoryDomain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface CbrCasesSuperseded {
    String tenantId();

    int supersededCount();

    Instant supersededAt();

    record ByCase(String tenantId, String caseId, String supersedingCaseId,
                  String reason, Instant supersededAt) implements CbrCasesSuperseded {
        public ByCase {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(supersededAt, "supersededAt");
        }

        @Override
        public int supersededCount() {
            return 1;
        }
    }

    record ByFilter(String tenantId, MemoryDomain domain, String caseType,
                    Map<String, CbrFilter> filters, String reason,
                    int supersededCount, Instant supersededAt) implements CbrCasesSuperseded {
        public ByFilter {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(caseType, "caseType");
            Objects.requireNonNull(filters, "filters");
            Objects.requireNonNull(supersededAt, "supersededAt");
            filters = Map.copyOf(filters);
        }
    }

    record ByIds(String tenantId, Collection<String> caseIds,
                 String reason, int supersededCount,
                 Instant supersededAt) implements CbrCasesSuperseded {
        public ByIds {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(caseIds, "caseIds");
            Objects.requireNonNull(supersededAt, "supersededAt");
            caseIds = List.copyOf(caseIds);
        }
    }
}
