package org.p32h.interop.epa.pave.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.PaveApplication;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

/**
 * Every recursion of the main code is named here with what bounds it.
 *
 * <p>The companion of {@link RegexStackGuardTest}. A method that calls itself, directly or through
 * others, takes a frame of the thread's stack for each turn, so a turn per item of something a
 * file or a service answer can make as long as it likes throws StackOverflowError, as an enableWhen
 * chain of a few thousand items in a flat list did. A recursion is acceptable only when what bounds
 * it is known: the depth of a JSON tree, which the parser holds to {@link #JACKSON_DEPTH} levels, or
 * a fixed number of turns the code itself sets.
 *
 * <p>The cycles are found in the compiled classes: each call from one method of the tool to another,
 * each lambda or method reference handed on (the body runs as a call from where it was written), and
 * each call to a method that a class of the tool overrides, as a call to every override. A call
 * made through a type of a library (a Comparator, a Function held in a field) is not seen; the
 * methods those reach are short and are named here when they recurse through the tool itself.
 *
 * <p>A new recursion fails this test until it is added to {@link #KNOWN} with its bound, and one
 * that is gone fails it until it is taken out, so the list stays the list of what there is.
 */
class RecursionGuardTest {

    /** Jackson 3's default StreamReadConstraints maximum nesting depth, the bound of every walk of a JSON tree. */
    static final int JACKSON_DEPTH = 500;

    private static final String ROOT = "org/p32h/interop/epa/pave/";

    /** Marks a call the JVM dispatches on the receiver's class, which may therefore run an override. */
    private static final String VIRTUAL = "virtual:";

    /** What bounds a recursion. */
    enum Bound {
        /**
         * One turn per level of a JSON tree the parser read (or a copy of one), or of a tree this tool
         * builds with a level for each of its levels, so at most {@link #JACKSON_DEPTH} turns.
         */
        JSON_DEPTH,
        /** A number of turns the code fixes, whatever the input. */
        FIXED,
        /** One turn per backslash in the value of a secret the tool sent, which the tester configures. */
        SECRET_TEXT
    }

    record Known(Bound bound, String why) {
    }

    private static Known json(String why) {
        return new Known(Bound.JSON_DEPTH, why);
    }

    private static Known fixed(String why) {
        return new Known(Bound.FIXED, why);
    }

    /**
     * Each cycle, by the methods in it (class within the tool's package, then the method name,
     * overloads together), with what bounds it. Filled from the scan, then each read.
     */
    static final Map<String, Known> KNOWN = new TreeMap<>(Map.ofEntries(
            Map.entry("client/crd/MockOnyxCrdClient.collectNamed", json("a level of the scenario's CRD answer per turn")),
            Map.entry("client/crd/MockOnyxCrdClient.collectQuestionnaires", json("a level of the scenario's CRD answer per turn")),
            Map.entry("client/crd/MockOnyxCrdClient.renameQuestionnaires", json("a level of the scenario's CRD answer per turn")),
            Map.entry("client/lookup/LookupEnvelope.holdsAValue | client/lookup/LookupEnvelope.lambda$holdsAValue$0 "
                    + "| client/lookup/LookupEnvelope.lambda$holdsAValue$1", json("a level of the lookup answer per turn")),
            Map.entry("client/pas/MockOnyxPasClient.replace", json("a level of the scenario's PAS answer per turn")),
            Map.entry("model/JsonSafe.walk", json("a level of the token stream of the text being parsed per turn: the parser refuses the "
                    + "level past its limit before this reaches it")),
            Map.entry("model/SentSecrets.endOfSecret", new Known(Bound.SECRET_TEXT, "a turn per backslash of the secret, the one "
                    + "character a JSON text can hold both as itself and as the start of an escape")),
            Map.entry("model/TrackedJson.leaves", json("a level of a service answer per turn")),
            Map.entry("model/TrackedJson.walk", json("a level of a service answer per turn")),
            Map.entry("model/dtr/QuestionnaireJson.buildItems", json("a level of the item tree collect builds per turn")),
            Map.entry("model/dtr/QuestionnaireJson.check", json("a level of nested items per turn")),
            Map.entry("model/dtr/QuestionnaireJson.collect", json("a level of nested items per turn")),
            Map.entry("model/dtr/QuestionnaireJson.collectAnswers", json("a level of nested response items per turn")),
            Map.entry("model/dtr/QuestionnaireJson.collectItems", json("a level of nested response items per turn")),
            Map.entry("model/dtr/QuestionnaireJson.findInParameters", json("a level of nested Parameters parts per turn")),
            Map.entry("model/dtr/QuestionnaireJson.flatten", json("a level of the item tree collect builds, one per level of nested "
                    + "items")),
            Map.entry("postman/PostmanParser.collectPlaceholders | postman/PostmanParser.lambda$collectPlaceholders$4 "
                    + "| postman/PostmanParser.lambda$collectPlaceholders$5", json("a level of a collection's JSON per turn")),
            Map.entry("postman/PostmanParser.walk", json("a folder of a collection per turn, two JSON levels each")),
            Map.entry("postman/VariableResolver$Run.expand | postman/VariableResolver$Run.variable", fixed("a variable per turn, "
                    + "stopped at VariableResolver.MAX_DEPTH (10) with a sentence that names the limit")),
            Map.entry("service/compare/JsonComparer$Run.arrays | service/compare/JsonComparer$Run.freeKeyed "
                    + "| service/compare/JsonComparer$Run.objects | service/compare/JsonComparer$Run.walk", fixed("a level of the "
                    + "two bodies compared per turn, stopped at JsonComparer.MAX_DEPTH (64) with a note that says so")),
            Map.entry("service/compare/ResourceIndex.walk", fixed("a level of a body per turn, stopped at JsonComparer.MAX_DEPTH (64)")),
            Map.entry("service/dtr/DtrFindings.items", json("a level of the item views the mapper built, one per level of nested items")),
            Map.entry("service/dtr/DtrRequestBuilder.withoutConditions", json("a level of nested items per turn")),
            Map.entry("service/dtr/DtrResponseMapper$Walk.bundle | service/dtr/DtrResponseMapper$Walk.parameters "
                    + "| service/dtr/DtrResponseMapper$Walk.resource", json("a Bundle or Parameters nested in another per turn")),
            Map.entry("service/dtr/DtrResponseMapper.item | service/dtr/DtrResponseMapper.items", json("a level of nested items per turn")),
            Map.entry("service/dtr/DtrResponseMapper.responseItems", json("a level of nested response items per turn")),
            Map.entry("service/dtr/DtrVerdicts.prepopulated", json("a level of the item views the mapper built per turn")),
            Map.entry("service/ledger/RunCsv.text", json("a level of a value read back from a ledger line per turn")),
            Map.entry("service/pas/NotificationReader.claimResponsesIn", json("a Bundle nested in another per turn")),
            Map.entry("service/pas/PasRequestBuilder.repoint", json("a level of the request bundle per turn")),
            Map.entry("service/pas/PasRequestBuilder.unresolvedIn", json("a level of the request bundle per turn")),
            Map.entry("service/pas/PasResponseMapper.extension", json("an extension nested in another per turn")),
            Map.entry("service/pas/PasResponseMapper.readBundle", json("a Bundle nested in another per turn")),
            Map.entry("service/run/MembersInBody.collect", fixed("a level of a request body per turn, stopped at 64 levels")),
            Map.entry("service/scenario/ScenarioRepository.collectTexts", json("a level of a scenario file per turn"))));

    @Test
    void everyRecursionOfTheMainCodeIsNamedWithWhatBoundsIt() throws IOException {
        Map<String, Set<String>> cycles = cycles(scan(mainClasses()));
        List<String> unlisted = cycles.keySet().stream().filter(key -> !KNOWN.containsKey(key)).toList();
        List<String> stale = KNOWN.keySet().stream().filter(key -> !cycles.containsKey(key)).toList();
        assertThat(unlisted).as("recursions the main code holds that KNOWN does not name with their bound").isEmpty();
        assertThat(stale).as("recursions KNOWN names that the main code no longer holds").isEmpty();
    }

    @Test
    void theScanFindsARecursionThroughALambdaAndThroughAnOverride() throws IOException {
        Map<String, Set<String>> cycles = cycles(scan(List.of(Samples.class, Samples.Base.class, Samples.Child.class)));
        assertThat(cycles).containsKey("style/RecursionGuardTest$Samples.direct");
        assertThat(cycles).containsKey("style/RecursionGuardTest$Samples.lambda$throughLambda$0 | style/RecursionGuardTest$Samples.throughLambda");
        // Child.step calls Base.step, which the JVM dispatches to Child.step.
        assertThat(cycles).containsKey("style/RecursionGuardTest$Samples$Child.step");
        assertThat(cycles).hasSize(3);
    }

    /** Shapes the scan must see, compiled with the tests but under the tool's package like the main code. */
    @SuppressWarnings("unused")
    static final class Samples {

        static int direct(int n) {
            return n <= 0 ? 0 : direct(n - 1);
        }

        static int throughLambda(List<Integer> values) {
            return values.stream().mapToInt(value -> throughLambda(List.of())).sum();
        }

        static int notRecursive(int n) {
            return direct(n);
        }

        abstract static class Base {
            abstract int step(Base next);
        }

        static final class Child extends Base {
            @Override
            int step(Base next) {
                return next == null ? 0 : next.step(null);
            }
        }
    }

    // ---- the scan ----

    private static List<Class<?>> mainClasses() throws IOException {
        Path root;
        try {
            root = Path.of(PaveApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                String name = root.relativize(file).toString().replace('\\', '/').replaceAll("\\.class$", "").replace('/', '.');
                try {
                    out.add(Class.forName(name, false, RecursionGuardTest.class.getClassLoader()));
                } catch (ClassNotFoundException | LinkageError e) {
                    throw new IllegalStateException("cannot load " + name, e);
                }
            }
        }
        assertThat(out).as("main classes found").hasSizeGreaterThan(300);
        return out;
    }

    /** The call graph: a method of the tool, as owner.name+descriptor, to the methods of the tool it may call. */
    private static Map<String, Set<String>> scan(List<Class<?>> classes) throws IOException {
        Map<String, Set<String>> calls = new HashMap<>();
        Map<String, Set<String>> declared = new HashMap<>();
        Map<String, Set<String>> subtypes = new HashMap<>();
        for (Class<?> type : classes) {
            String resource = type.getName().replace('.', '/') + ".class";
            try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("no class file for " + type.getName());
                }
                new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                    private String owner;

                    @Override
                    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                        owner = name;
                        if (superName != null) {
                            subtypes.computeIfAbsent(superName, k -> new LinkedHashSet<>()).add(name);
                        }
                        for (String implemented : interfaces == null ? new String[0] : interfaces) {
                            subtypes.computeIfAbsent(implemented, k -> new LinkedHashSet<>()).add(name);
                        }
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        String from = owner + "." + name + descriptor;
                        declared.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(name + descriptor);
                        Set<String> out = calls.computeIfAbsent(from, k -> new LinkedHashSet<>());
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String target, String method, String desc, boolean isInterface) {
                                if (target.startsWith(ROOT)) {
                                    boolean dispatched = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE;
                                    out.add((dispatched ? VIRTUAL : "") + target + "." + method + desc);
                                }
                            }

                            @Override
                            public void visitInvokeDynamicInsn(String method, String desc, Handle bootstrap, Object... arguments) {
                                for (Object argument : arguments) {
                                    if (argument instanceof Handle handle && handle.getOwner().startsWith(ROOT)) {
                                        out.add(handle.getOwner() + "." + handle.getName() + handle.getDesc());
                                    }
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        // A call to a method a subtype overrides may run the override.
        Map<String, Set<String>> graph = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : calls.entrySet()) {
            Set<String> targets = new LinkedHashSet<>();
            for (String call : entry.getValue()) {
                boolean dispatched = call.startsWith(VIRTUAL);
                String target = dispatched ? call.substring(VIRTUAL.length()) : call;
                targets.add(target);
                if (!dispatched) {
                    continue;
                }
                int dot = target.indexOf('.');
                String owner = target.substring(0, dot);
                String method = target.substring(dot + 1);
                Deque<String> below = new ArrayDeque<>(subtypes.getOrDefault(owner, Set.of()));
                Set<String> seen = new LinkedHashSet<>();
                while (!below.isEmpty()) {
                    String sub = below.pop();
                    if (!seen.add(sub)) {
                        continue;
                    }
                    if (declared.getOrDefault(sub, Set.of()).contains(method)) {
                        targets.add(sub + "." + method);
                    }
                    below.addAll(subtypes.getOrDefault(sub, Set.of()));
                }
            }
            graph.put(entry.getKey(), targets);
        }
        return graph;
    }

    /**
     * The strongly connected parts of the graph that hold a cycle, each by its key: the sorted
     * owner.method names with the package root and the descriptors left off. Tarjan's algorithm,
     * with a stack of its own so that the scan itself does not recurse.
     */
    static Map<String, Set<String>> cycles(Map<String, Set<String>> graph) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new java.util.HashSet<>();
        Map<String, Set<String>> out = new TreeMap<>();
        int[] counter = {0};
        for (String start : new TreeSet<>(graph.keySet())) {
            if (index.containsKey(start)) {
                continue;
            }
            Deque<Map.Entry<String, java.util.Iterator<String>>> work = new ArrayDeque<>();
            index.put(start, counter[0]);
            low.put(start, counter[0]++);
            stack.push(start);
            onStack.add(start);
            work.push(Map.entry(start, graph.getOrDefault(start, Set.of()).iterator()));
            while (!work.isEmpty()) {
                String node = work.peek().getKey();
                java.util.Iterator<String> next = work.peek().getValue();
                if (next.hasNext()) {
                    String target = next.next();
                    if (!index.containsKey(target)) {
                        index.put(target, counter[0]);
                        low.put(target, counter[0]++);
                        stack.push(target);
                        onStack.add(target);
                        work.push(Map.entry(target, graph.getOrDefault(target, Set.of()).iterator()));
                    } else if (onStack.contains(target)) {
                        low.put(node, Math.min(low.get(node), index.get(target)));
                    }
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    String parent = work.peek().getKey();
                    low.put(parent, Math.min(low.get(parent), low.get(node)));
                }
                if (low.get(node).equals(index.get(node))) {
                    List<String> component = new ArrayList<>();
                    String member;
                    do {
                        member = stack.pop();
                        onStack.remove(member);
                        component.add(member);
                    } while (!member.equals(node));
                    if (component.size() > 1 || graph.getOrDefault(node, Set.of()).contains(node)) {
                        Set<String> names = new TreeSet<>();
                        for (String method : component) {
                            names.add(method.substring(ROOT.length(), method.indexOf('(')));
                        }
                        out.put(String.join(" | ", names), new LinkedHashSet<>(component));
                    }
                }
            }
        }
        return out;
    }

}
