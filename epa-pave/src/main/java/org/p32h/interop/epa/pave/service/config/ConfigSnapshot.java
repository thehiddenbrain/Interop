package org.p32h.interop.epa.pave.service.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *                     to it, so it is never short of them. An address of one of them that cannot
 *                     be read is there as its whole host, see {@link ProductionDestinations#production}
 * @param applicationYml the {@code onyx.variables} of application.yml, the last layer a url is
 *                     resolved through. The addresses of a production environment are read through
 *                     it as well, because a write goes wherever it says
 */
record ConfigSnapshot(long epoch, List<StoredEnvironment> environments, String activeName, List<StoredCollection> collections,
                      List<KnownDestination> remembered, Map<String, String> applicationYml) {

    ConfigSnapshot {
        environments = environments == null ? List.of() : List.copyOf(environments);
        collections = collections == null ? List.of() : List.copyOf(collections);
        applicationYml = applicationYml == null ? Map.of() : Map.copyOf(applicationYml);
        Set<KnownDestination> all = new LinkedHashSet<>(remembered == null ? List.of() : remembered);
        ProductionDestinations.Context context = ProductionDestinations.Context.of(collections, applicationYml);
        Map<String, List<ProductionDestinations.Found>> production = new java.util.LinkedHashMap<>();
        for (StoredEnvironment environment : environments) {
            if (environment.kind() == EnvironmentKind.PROD) {
                production.put(environment.name(), ProductionDestinations.production(environment.scanned(), context));
            }
        }
        // An address the environment leaves to a collection or application.yml is shared by every
        // environment that does the same. It is that environment's only where no other production
        // environment names it as its own: otherwise an unlock of one would carry a write to the
        // other's address.
        production.forEach((name, found) -> found.forEach(each -> {
            boolean anothersOwn = !each.own() && production.entrySet().stream().anyMatch(other -> !other.getKey().equals(name)
                    && other.getValue().stream().anyMatch(theirs -> theirs.own() && theirs.destination().overlaps(each.destination())));
            if (!anothersOwn) {
                all.add(new KnownDestination(name, each.destination(), each.secret()));
            }
        }));
        remembered = List.copyOf(all);
    }

    ConfigSnapshot(long epoch, List<StoredEnvironment> environments, String activeName, List<StoredCollection> collections,
                   List<KnownDestination> remembered) {
        this(epoch, environments, activeName, collections, remembered, Map.of());
    }

    ConfigSnapshot(long epoch, List<StoredEnvironment> environments, String activeName, List<StoredCollection> collections) {
        this(epoch, environments, activeName, collections, List.of());
    }

    /** The layers a url is resolved through after the environment, for reading the addresses of an environment. */
    ProductionDestinations.Context context() {
        return ProductionDestinations.Context.of(collections, applicationYml);
    }

    /** Prints counts and names, never a value of application.yml, which can be a secret. */
    @Override
    public String toString() {
        return "ConfigSnapshot[epoch=" + epoch + ", environments=" + environments.size() + ", active=" + activeName + ", collections="
                + collections.size() + ", remembered=" + remembered.size() + ", applicationYml=" + applicationYml.keySet() + "]";
    }

    /**
     * A destination known to be production.
     *
     * @param environment the production environment it belongs or belonged to
     * @param destination scheme, host, port and base path
     * @param secret      true when a secret of that environment is part of its host or port. It is
     *                    judged by while it is known, never shown, and never written to the store,
     *                    so it is forgotten at a restart once its environment is gone
     */
    record KnownDestination(String environment, UrlNormal.Destination destination, boolean secret) {

        KnownDestination(String environment, UrlNormal.Destination destination) {
            this(environment, destination, false);
        }

        /** Prints the environment only: the destination can be one built from a secret. */
        @Override
        public String toString() {
            return "KnownDestination[" + environment + (secret ? ", secret" : "") + "]";
        }
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
