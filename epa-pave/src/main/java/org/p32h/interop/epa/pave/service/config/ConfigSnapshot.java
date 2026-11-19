package org.p32h.interop.epa.pave.service.config;

import java.util.List;
import org.p32h.interop.epa.pave.model.EnvironmentRef;

/**
 * The whole stored configuration at one moment. It never changes: every change to the store builds
 * a new one with the next epoch.
 *
 * <p>The gateway takes one of these at the start of a call and does everything against it, so a
 * url is never resolved from one environment and judged against another.
 *
 * @param epoch        the configuration epoch this snapshot is
 * @param environments every uploaded environment, in the order they were first uploaded
 * @param activeName   the name of the active environment, or null when none is active
 * @param collections  every uploaded collection, in the order they were first uploaded
 */
record ConfigSnapshot(long epoch, List<StoredEnvironment> environments, String activeName, List<StoredCollection> collections) {

    ConfigSnapshot {
        environments = environments == null ? List.of() : List.copyOf(environments);
        collections = collections == null ? List.of() : List.copyOf(collections);
    }

    /** The active environment, or null. */
    StoredEnvironment active() {
        return activeName == null ? null : byName(activeName);
    }

    EnvironmentRef activeRef() {
        StoredEnvironment active = active();
        return active == null ? EnvironmentRef.none() : active.ref();
    }

    StoredEnvironment byName(String name) {
        if (name == null) {
            return null;
        }
        for (StoredEnvironment environment : environments) {
            if (environment.name().equals(name)) {
                return environment;
            }
        }
        return null;
    }

    /**
     * The environment with this exact name, or failing that the one with this id. The id exists
     * because a name can hold a slash or a semicolon, which cannot travel in one segment of a url
     * path; the id always can.
     */
    StoredEnvironment environment(String nameOrId) {
        StoredEnvironment named = byName(nameOrId);
        if (named != null || nameOrId == null) {
            return named;
        }
        for (StoredEnvironment environment : environments) {
            if (environment.id().equals(nameOrId)) {
                return environment;
            }
        }
        return null;
    }

    /** The collection with the given id, or null. */
    StoredCollection collection(String id) {
        if (id == null) {
            return null;
        }
        for (StoredCollection collection : collections) {
            if (collection.id().equals(id)) {
                return collection;
            }
        }
        return null;
    }
}
