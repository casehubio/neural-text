package io.casehub.neocortex.memory.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import java.util.Set;

public interface CbrCaseAdmin {
    default Set<String> discoverTenants(MemoryDomain domain) {
        throw new UnsupportedOperationException(
                "discoverTenants not supported by " + getClass().getSimpleName());
    }

    Integer purge(CbrRetentionPolicy policy);
}
