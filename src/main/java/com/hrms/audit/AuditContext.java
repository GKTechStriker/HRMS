package com.hrms.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Thread-local context allowing service methods to attach targetId, previousData,
 * and newData snapshots to the current @Audited operation (Section 7.2).
 */
public final class AuditContext {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private AuditContext() {
    }

    public static void setTarget(UUID targetId, Map<String, Object> previousData, Map<String, Object> newData) {
        CURRENT.set(new Snapshot(
                targetId,
                previousData != null ? new LinkedHashMap<>(previousData) : null,
                newData != null ? new LinkedHashMap<>(newData) : null
        ));
    }

    public static Snapshot get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Snapshot(UUID targetId, Map<String, Object> previousData, Map<String, Object> newData) {
    }
}
