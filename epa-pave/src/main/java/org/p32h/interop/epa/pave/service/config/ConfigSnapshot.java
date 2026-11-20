package org.p32h.interop.epa.pave.service.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.postman.UrlNormal;

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
 * @param remembered   every production destination this store has known, each with the
 *                     production environment it belonged to. A deletion does not shorten it, so
 *                     an address that was production stays production after its environment is
 *                     gone. The destinations of the production environments stored now are added
 *                     to it, so it is never short of them
 */
record ConfigSnapshot(long epoch, List<StoredEnvironment> environments, String activeName, List<StoredCollection> collections,
                      List<KnownDestination> remembered) {

    ConfigSnapshot {
        environments = environments == null ? List.of() : List.copyOf(environments);
        collections = collections == null ? List.of() : List.copyOf(collections);
        Set<KnownDestination> all = new LinkedHashSet<>(remembered == null ? List.of() : remembered);
        Set<String> urlForming = ProductionDestinations.urlForming(collections);
        for (StoredEnvironment environment : environments) {
            if (environment.kind() == EnvironmentKind.PROD) {
                for (UrlNormal.Destination destination : ProductionDestinations.of(environment.content(), urlForming)) {
                    all.add(new KnownDestination(environment.name(), destination));
                }
            }
        }
        remembered = List.copyOf(all);
    }

    ConfigSnapshot(long epoch, List<StoredEnvironment> environments, String activeName, List<StoredCollection> collections) {
        this(epoch, environments, activeName, collections, List.of());
    }

    /**
     * A destination known to be production.
     *
     * @param environment the production environment it belongs or belonged to
     * @param destination scheme, host, port and base path
     */
    record KnownDestination(String environment, UrlNormal.Destination destination) {
    }

    /** The production environments, stored or remembered, whose destinations cover this one. Empty when none does. */
    List<String> productionOwners(UrlNormal.Destination destination) {
        List<String> owners = new ArrayList<>();
        if (destination == null) {
            return owners;
        }
        for (KnownDestination known : remembered) {
            if (known.destination().covers(destination) && !owners.contains(known.environment())) {
                owners.add(known.environment());
            }
        }
        return owners;
    }

    /** The production environments, stored or remembered, a destination of another environment would share an address with. */
    List<String> productionOverlaps(UrlNormal.Destination destination) {
        List<String> owners = new ArrayList<>();
        for (KnownDestination known : remembered) {
            if (known.destination().overlaps(destination) && !owners.contains(known.environment())) {
                owners.add(known.environment());
            }
        }
        return owners;
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
