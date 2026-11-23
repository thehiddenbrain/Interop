package org.p32h.interop.epa.pave.service.lists;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.postman.Names;
import org.p32h.interop.epa.pave.postman.PostmanCollection;
import org.p32h.interop.epa.pave.postman.PostmanEnvironment;
import org.p32h.interop.epa.pave.postman.PostmanParser;
import org.p32h.interop.epa.pave.postman.VariableLayer;
import org.p32h.interop.epa.pave.postman.VariableResolver;
import org.p32h.interop.epa.pave.service.config.ConfigService;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentList;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.VariableView;
import org.p32h.interop.epa.pave.service.error.FileGuard;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.lists.FoundViews.Added;
import org.p32h.interop.epa.pave.service.lists.FoundViews.Counts;
import org.p32h.interop.epa.pave.service.lists.FoundViews.ExportText;
import org.p32h.interop.epa.pave.service.lists.FoundViews.Finds;
import org.p32h.interop.epa.pave.service.lists.FoundViews.Found;
import org.p32h.interop.epa.pave.service.lists.FoundViews.FoundRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Reads the members, providers and procedure codes an uploaded collection's requests state, and
 * adds them to the saved lists when the tester asks. The same on mock and on live: what a vendor's
 * sample states is the tester's test data whichever profile runs.
 *
 * <p>Reading stores nothing and sends nothing. The collection is read inside the file guard, so
 * whatever the file holds the answer is a line about it, never an internal error (R-07). The log
 * holds counts only.
 */
@Service
public class CollectionIngest {

    private static final Logger log = LoggerFactory.getLogger(CollectionIngest.class);

    static final String NOTHING = "Nothing to add to the saved lists was found in the collection.";
    static final String CHOOSE = "Choose the collection export to read members, providers and codes from.";

    private final Supplier<EnvironmentList> stored;
    private final TestListService lists;
    private final VariableResolver resolver;

    @Autowired
    public CollectionIngest(ConfigService config, TestListService lists, Clock clock) {
        this(config::environments, lists, VariableResolver.forProcess(clock));
    }

    /** As the application's constructor, with the stored environments and the resolver given, for tests. */
    public CollectionIngest(Supplier<EnvironmentList> stored, TestListService lists, VariableResolver resolver) {
        this.stored = stored;
        this.lists = lists;
        this.resolver = resolver;
    }

    /** What the collection states. */
    public Found found(FoundRequest request) {
        if (!lists.enabled()) {
            return new Found(false, TestListService.OFF, null, null, null, null, null, null, null);
        }
        Read read = read(request);
        if (read.finds() == null) {
            return new Found(true, read.line(), read.reference(), read.cause(), null, null, null, null, read.notes());
        }
        Finds finds = read.finds();
        List<String> notUsed = new ArrayList<>(read.notes());
        notUsed.addAll(finds.notUsed());
        // Counts only. Never a value found.
        log.info("Read a collection for the saved lists: {} members, {} providers, {} codes, {} diagnosis codes, {} lines not used",
                finds.members().size(), finds.providers().size(), finds.codes().size(), finds.diagnoses().size(), notUsed.size());
        return new Found(true, finds.empty() ? NOTHING : null, null, null, finds.members(), finds.providers(), finds.codes(),
                finds.diagnoses(), notUsed);
    }

    /**
     * Reads the collection again, as the page posts it on "Save environment" with the box ticked or
     * on "Add to the saved lists", and adds what it states. Reading again, rather than taking the
     * entries the page shows, keeps the rules of the reading on the server, where they are tested.
     */
    public Added add(FoundRequest request) {
        if (!lists.enabled()) {
            return new Added(false, TestListService.OFF, null, null, null, null, null, null);
        }
        Read read = read(request);
        if (read.finds() == null) {
            return new Added(true, read.line(), read.reference(), read.cause(), null, null, null, read.notes());
        }
        if (read.finds().empty()) {
            return new Added(true, NOTHING, null, null, null, null, null, null);
        }
        TestListService.FoundAdd done = lists.addFound(read.finds());
        return new Added(true, line(done), null, null, done.members(), done.providers(), done.codes(), done.notAdded());
    }

    /** "Added to the saved lists: 3 members, 2 providers, 4 procedure codes. Already there: 1 member." */
    static String line(TestListService.FoundAdd done) {
        Counts[] counts = {done.members(), done.providers(), done.codes()};
        String[][] words = {{"member", "members"}, {"provider", "providers"}, {"procedure code", "procedure codes"}};
        List<String> added = new ArrayList<>();
        List<String> already = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i].added() > 0) {
                added.add(counted(counts[i].added(), words[i]));
            }
            if (counts[i].already() > 0) {
                already.add(counted(counts[i].already(), words[i]));
            }
            if (counts[i].refused() > 0) {
                refused.add(counted(counts[i].refused(), words[i]));
            }
            if (counts[i].full() > 0) {
                full.append(" The ").append(words[i][1]).append(" list is full (").append(TestListRules.MAX_ENTRIES).append(" entries): ")
                        .append(counted(counts[i].full(), words[i])).append(" did not fit.");
            }
        }
        StringBuilder line = new StringBuilder(added.isEmpty() ? "Nothing new was added to the saved lists."
                : "Added to the saved lists: " + String.join(", ", added) + ".");
        if (!already.isEmpty()) {
            line.append(" Already there: ").append(String.join(", ", already)).append('.');
        }
        line.append(full);
        if (!refused.isEmpty()) {
            line.append(" Not added, as the lists' rules refuse them: ").append(String.join(", ", refused)).append('.');
        }
        return line.toString();
    }

    private static String counted(int count, String[] words) {
        return count + " " + (count == 1 ? words[0] : words[1]);
    }

    /**
     * What reading gave: the finds, or the line that says why there are none.
     *
     * @param notes what the reader says about the files beside the collection, such as an
     *              environment export that could not be read
     */
    record Read(Finds finds, String line, String reference, String cause, List<String> notes) {

        @Override
        public String toString() {
            return "Read[" + (finds == null ? "not read" : finds) + "]";
        }
    }

    Read read(FoundRequest request) {
        ExportText collectionFile = request == null ? null : request.collectionExport();
        if (collectionFile == null || Texts.blank(collectionFile.text())) {
            throw ValidationException.of("collectionExport", CHOOSE);
        }
        List<String> notes = new ArrayList<>();
        List<VariableLayer> layers = new ArrayList<>();
        VariableLayer environment = environmentLayer(request, notes);
        if (environment != null) {
            layers.add(environment);
        }
        FileGuard.Read<Finds> read = FileGuard.read(collectionFile.fileName(), () -> {
            PostmanCollection collection = PostmanParser.collection(collectionFile.text());
            List<VariableLayer> all = new ArrayList<>(layers);
            all.add(VariableLayer.collection(collection.name(), collection.inForce()));
            return CollectionFinds.read(collection, all, resolver);
        });
        if (!read.ok()) {
            return new Read(null, "The collection was not read: " + read.line(), read.reference(), read.cause(), notes);
        }
        return new Read(read.value(), null, null, null, notes);
    }

    /** The environment whose variables fill the collection's placeholders, or null for none. */
    private VariableLayer environmentLayer(FoundRequest request, List<String> notes) {
        ExportText environmentFile = request.environmentExport();
        if (environmentFile != null && Texts.present(environmentFile.text())) {
            FileGuard.Read<PostmanEnvironment> read = FileGuard.read(environmentFile.fileName(),
                    () -> PostmanParser.environment(environmentFile.text()));
            if (read.ok()) {
                return VariableLayer.environment(read.value().name(), read.value().inForce());
            }
            notes.add("The environment export was not read, so the collection's placeholders were filled from the collection alone.");
            return null;
        }
        EnvironmentView view = null;
        if (Texts.present(request.forId())) {
            String id = request.forId().strip();
            view = stored.get().environments().stream().filter(each -> id.equals(each.id())).findFirst().orElse(null);
            if (view == null) {
                throw new NotFoundException("No environment with this id is stored: it may have been removed since the form opened.",
                        "Close the form and open the environment again from its card.");
            }
        } else if (Boolean.TRUE.equals(request.fromActiveEnvironment())) {
            view = stored.get().environments().stream().filter(EnvironmentView::active).findFirst().orElse(null);
        }
        return view == null ? null : storedLayer(view);
    }

    /**
     * A stored environment's variables as the store shows them: a value that is not a secret as
     * written, a secret as its mask and typed secret, so that a body which uses one is not read.
     */
    static VariableLayer storedLayer(EnvironmentView view) {
        Map<String, VariableLayer.Entry> entries = new LinkedHashMap<>();
        for (VariableView variable : view.variables()) {
            if (variable.inForce() && variable.set()) {
                entries.put(variable.name(), new VariableLayer.Entry(variable.display(), variable.secret()));
            }
        }
        return new VariableLayer(VariableSource.ENVIRONMENT, "the environment \"" + Names.shown(view.name()) + "\"", entries);
    }

    @Override
    public String toString() {
        return "CollectionIngest[]";
    }
}
