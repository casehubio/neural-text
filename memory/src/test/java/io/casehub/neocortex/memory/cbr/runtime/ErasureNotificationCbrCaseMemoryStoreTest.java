package io.casehub.neocortex.memory.cbr.runtime;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrCasesErased;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.platform.api.path.Path;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class ErasureNotificationCbrCaseMemoryStoreTest {

    private static final Instant FIXED = Instant.parse("2026-07-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);
    private static final MemoryDomain CBR = new MemoryDomain("cbr");

    private StubStore stub;
    private List<CbrCasesErased.ByRequest> byRequestEvents;
    private List<CbrCasesErased.ByEntity> byEntityEvents;
    private List<CbrCasesErased.ByScope> byScopeEvents;
    private ErasureNotificationCbrCaseMemoryStore decorator;

    @BeforeEach
    void setUp() {
        stub = new StubStore();
        byRequestEvents = new ArrayList<>();
        byEntityEvents = new ArrayList<>();
        byScopeEvents = new ArrayList<>();
        decorator = new ErasureNotificationCbrCaseMemoryStore(
                stub,
                capturingEvent(byRequestEvents),
                capturingEvent(byEntityEvents),
                capturingEvent(byScopeEvents),
                CLOCK);
    }

    @Test
    void erase_firesEvent_whenCountPositive() {
        stub.eraseReturnValue = 3;
        var request = new EraseRequest("e-1", CBR, "t-1", "c-1");
        int result = decorator.erase(request);
        assertThat(result).isEqualTo(3);
        assertThat(byRequestEvents).hasSize(1);
        var event = byRequestEvents.getFirst();
        assertThat(event.tenantId()).isEqualTo("t-1");
        assertThat(event.erasedCount()).isEqualTo(3);
        assertThat(event.entityId()).isEqualTo("e-1");
        assertThat(event.domain()).isEqualTo(CBR);
        assertThat(event.caseId()).isEqualTo("c-1");
        assertThat(event.erasedAt()).isEqualTo(FIXED);
    }

    @Test
    void erase_noEvent_whenCountZero() {
        stub.eraseReturnValue = 0;
        decorator.erase(new EraseRequest("e-1", CBR, "t-1", null));
        assertThat(byRequestEvents).isEmpty();
    }

    @Test
    void eraseEntity_firesEvent_whenCountPositive() {
        stub.eraseEntityReturnValue = 5;
        int result = decorator.eraseEntity("e-1", "t-1");
        assertThat(result).isEqualTo(5);
        assertThat(byEntityEvents).hasSize(1);
        var event = byEntityEvents.getFirst();
        assertThat(event.tenantId()).isEqualTo("t-1");
        assertThat(event.erasedCount()).isEqualTo(5);
        assertThat(event.entityId()).isEqualTo("e-1");
        assertThat(event.erasedAt()).isEqualTo(FIXED);
    }

    @Test
    void eraseEntity_noEvent_whenCountZero() {
        stub.eraseEntityReturnValue = 0;
        decorator.eraseEntity("e-1", "t-1");
        assertThat(byEntityEvents).isEmpty();
    }

    @Test
    void eraseByScope_firesEvent_whenCountPositive() {
        stub.eraseByScopeReturnValue = 7;
        int result = decorator.eraseByScope(Path.of("org", "site"), "t-1");
        assertThat(result).isEqualTo(7);
        assertThat(byScopeEvents).hasSize(1);
        var event = byScopeEvents.getFirst();
        assertThat(event.tenantId()).isEqualTo("t-1");
        assertThat(event.erasedCount()).isEqualTo(7);
        assertThat(event.scope()).isEqualTo(Path.of("org", "site"));
        assertThat(event.erasedAt()).isEqualTo(FIXED);
    }

    @Test
    void eraseByScope_noEvent_whenCountZero() {
        stub.eraseByScopeReturnValue = 0;
        decorator.eraseByScope(Path.of("org"), "t-1");
        assertThat(byScopeEvents).isEmpty();
    }

    @Test
    void purge_doesNotFireEvent() {
        stub.purgeReturnValue = 10;
        decorator.purge(new CbrRetentionPolicy("t-1", CBR, null, 30, null, null));
        assertThat(byRequestEvents).isEmpty();
        assertThat(byEntityEvents).isEmpty();
        assertThat(byScopeEvents).isEmpty();
    }

    @Test
    void allOtherMethods_delegateWithoutEvents() {
        decorator.registerSchema(null);
        decorator.store(null, null, null, null, null, null, Path.root());
        decorator.recordOutcome(null, null, null);
        decorator.supersede("c", "t", "s", "r");
        decorator.reinstate("c", "t");
        assertThat(byRequestEvents).isEmpty();
        assertThat(byEntityEvents).isEmpty();
        assertThat(byScopeEvents).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static <T> Event<T> capturingEvent(List<T> captured) {
        return new Event<>() {
            @Override public void fire(T event) { captured.add(event); }
            @Override public <U extends T> CompletionStage<U> fireAsync(U event) { return CompletableFuture.completedFuture(event); }
            @Override public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) { return CompletableFuture.completedFuture(event); }
            @Override public Event<T> select(Annotation... qualifiers) { return this; }
            @Override public <U extends T> Event<U> select(Class<U> subtype, Annotation... qualifiers) { return (Event<U>) this; }
            @Override public <U extends T> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { return (Event<U>) this; }
        };
    }

    private static class StubStore implements CbrCaseMemoryStore {
        int eraseReturnValue = 0;
        int eraseEntityReturnValue = 0;
        int eraseByScopeReturnValue = 0;
        int purgeReturnValue = 0;

        @Override public void registerSchema(CbrFeatureSchema schema) {}
        @Override public String store(CbrCase c, String ct, String e, MemoryDomain d, String t, String ci, Path scope) { return ""; }
        @Override public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery q, Class<C> ct) { return List.of(); }
        @Override public Integer erase(EraseRequest request) { return eraseReturnValue; }
        @Override public Integer eraseEntity(String entityId, String tenantId) { return eraseEntityReturnValue; }
        @Override public Integer eraseByScope(Path scope, String tenantId) { return eraseByScopeReturnValue; }
        @Override public void recordOutcome(String caseId, String tenantId, CbrOutcome outcome) {}
        @Override public Integer purge(CbrRetentionPolicy policy) { return purgeReturnValue; }
        @Override public boolean supersede(String caseId, String tenantId, String supersedingCaseId, String reason) { return false; }
        @Override public boolean reinstate(String caseId, String tenantId) { return false; }

        @Override public io.casehub.neocortex.memory.cbr.SupersessionStatus getSupersessionStatus(String caseId, String tenantId) { return io.casehub.neocortex.memory.cbr.SupersessionStatus.NOT_SUPERSEDED; }
        @Override public java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus> findSupersededCases(String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) { return java.util.List.of(); }
        @Override public java.util.List<String> findCaseIds(String t, io.casehub.neocortex.memory.MemoryDomain d, String ct, java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> f) { return java.util.List.of(); }
        @Override public int supersedeMatching(String t, io.casehub.neocortex.memory.MemoryDomain d, String ct, java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> f, String r) { return 0; }
        @Override public int supersedeAll(java.util.Collection<String> ids, String t, String r) { return 0; }
        @Override public int reinstateMatching(String t, io.casehub.neocortex.memory.MemoryDomain d, String ct, java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> f) { return 0; }
        @Override public int reinstateAll(java.util.Collection<String> ids, String t) { return 0; }

    }
}
