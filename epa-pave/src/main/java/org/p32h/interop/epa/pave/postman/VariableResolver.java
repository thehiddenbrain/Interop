package org.p32h.interop.epa.pave.postman;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.postman.Resolution.Piece;

/**
 * Resolves {@code {{placeholder}}} references against the layers a call is given.
 *
 * <p>The rules, each of which differs from "substitute whatever is there":
 * <ul>
 *   <li><b>A blank value is no value.</b> A variable that is defined and blank does not resolve at
 *       that layer; the next layer is asked, and when none has a value the placeholder is
 *       unresolved. Postman would substitute an empty string, which turns {@code {{baseUrl}}/x}
 *       into a url that fails like a network problem.</li>
 *   <li><b>Values may refer to other variables.</b> They are resolved through every layer, to a
 *       fixed depth, and a variable that refers back to itself is unresolved.</li>
 *   <li><b>A value typed for the run wins for any name</b>, including the two special forms below.</li>
 *   <li><b>{@code {{env:PAVE_NAME}}}</b> is read from the operating system variable
 *       {@code PAVE_NAME} at the moment of resolution, so a secret never has to sit in a file. Only
 *       names that start with {@code PAVE_} can be read, so an uploaded environment cannot read
 *       arbitrary variables of the server process and put them in a url. A value read this way is
 *       always a secret.</li>
 *   <li><b>{@code {{$guid}}}, {@code {{$randomUUID}}}, {@code {{$timestamp}}} and
 *       {@code {{$isoTimestamp}}}</b> are generated, since they need no script. Each use gets its
 *       own value, as in Postman. Any other {@code $name} is something Postman would have computed
 *       and this tool does not.</li>
 *   <li><b>A secret is masked by its name</b>, wherever its value would appear in the form that is
 *       shown, including when it is reached through another variable.</li>
 * </ul>
 *
 * <p>Nothing here logs, and no message it writes contains a value.
 */
public final class VariableResolver {

    /** How many variables deep a value may refer to another before resolution gives up. */
    public static final int MAX_DEPTH = 10;

    /** How much text variable values may expand to in one template. A value that refers to itself by way of many copies stops here. */
    static final long MAX_EXPANSION = 16_000_000L;

    /** The prefix of a placeholder that is read from the operating system. */
    public static final String OS_REFERENCE = "env:";

    /** The only operating system variables that can be read are the ones named for this tool. */
    public static final String OS_NAME_PREFIX = "PAVE_";

    private static final Pattern OS_NAME = Pattern.compile(OS_NAME_PREFIX + "[A-Za-z0-9_]+");

    /** The form JavaScript gives a date, which is what Postman's own timestamp variable produces. */
    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Function<String, String> operatingSystem;
    private final Clock clock;
    private final Supplier<String> guids;

    /**
     * @param operatingSystem looks up an operating system variable by name. Passed in so that tests
     *                        do not depend on the environment of whoever runs the build
     * @param guids           supplies the value of each generated identifier
     */
    public VariableResolver(Function<String, String> operatingSystem, Clock clock, Supplier<String> guids) {
        this.operatingSystem = operatingSystem;
        this.clock = clock;
        this.guids = guids;
    }

    /** The resolver the running application uses: the real operating system environment and random identifiers. */
    public static VariableResolver forProcess(Clock clock) {
        return new VariableResolver(System::getenv, clock, () -> UUID.randomUUID().toString());
    }

    /**
     * Resolves a template. Never throws for a placeholder with no value: the result says which
     * names are unresolved and why.
     *
     * @param layers where values come from, in the order they are asked
     */
    public Resolution resolve(String template, List<VariableLayer> layers) {
        String text = template == null ? "" : template;
        Run run = new Run(layers == null ? List.of() : layers);
        List<Piece> pieces = new ArrayList<>();
        run.expand(text, new ArrayDeque<>(), pieces, null, new LinkedHashSet<>());
        return new Resolution(text, pieces, run.uses, List.copyOf(run.unresolved), run.reasons, run.explanations);
    }

    /** The state of one resolution. */
    private final class Run {

        private record Found(VariableLayer layer, String value) {
        }

        private final List<VariableLayer> layers;
        private final Set<String> typedSecret = new HashSet<>();
        private final List<VariableUse> uses = new ArrayList<>();
        private final Set<String> described = new HashSet<>();
        private final Set<String> unresolved = new LinkedHashSet<>();
        private final Map<String, String> reasons = new LinkedHashMap<>();
        private final Map<String, String> explanations = new LinkedHashMap<>();
        private long expanded;
        private boolean overflow;

        private Run(List<VariableLayer> layers) {
            this.layers = layers;
            // A name Postman typed as secret in any layer is a secret whichever layer supplies its
            // value. A tester who types over a secret for one run has typed a secret.
            for (VariableLayer layer : layers) {
                layer.entries().forEach((key, entry) -> {
                    if (entry.secretType()) {
                        typedSecret.add(key);
                    }
                });
            }
        }

        /** Expands a template into pieces. False when anything in it has no value. */
        private boolean expand(String template, Deque<String> stack, List<Piece> out, String owner, Set<String> missing) {
            boolean complete = true;
            for (Placeholders.Part part : Placeholders.parts(template)) {
                if (!part.placeholder()) {
                    out.add(owner == null ? Piece.literal(part.literal()) : Piece.partOf(owner, part.literal()));
                } else if (!variable(part.name(), stack, out, missing)) {
                    complete = false;
                }
            }
            return complete;
        }

        private boolean variable(String name, Deque<String> stack, List<Piece> out, Set<String> missing) {
            boolean secret = Secrets.looksSecret(name) || typedSecret.contains(name) || name.startsWith(OS_REFERENCE);
            if (overflow) {
                return missing(name, secret, out, missing, "Resolution stopped before reaching it, because the text had already grown "
                        + "larger than this tool resolves.");
            }
            if (stack.contains(name)) {
                return missing(name, secret, out, missing, "Its value refers back to itself (" + chain(stack, name) + ").");
            }
            if (stack.size() >= MAX_DEPTH) {
                return missing(name, secret, out, missing, "It is nested more than " + MAX_DEPTH + " variables deep.");
            }
            Found found = find(name);
            if (found == null) {
                if (name.startsWith(OS_REFERENCE)) {
                    return operatingSystem(name, name, null, out, missing);
                }
                if (name.startsWith("$")) {
                    return generated(name, secret, out, missing);
                }
                return missing(name, secret, out, missing, blankReason(name));
            }

            String value = found.value();
            String pointer = Placeholders.sole(value);
            if (pointer != null && pointer.startsWith(OS_REFERENCE) && find(pointer) == null) {
                return operatingSystem(name, pointer, found.layer(), out, missing);
            }

            if (!Placeholders.any(value)) {
                expanded += value.length();
                if (expanded > MAX_EXPANSION) {
                    overflow = true;
                    return missing(name, secret, out, missing, "Its value expands to more text than this tool resolves.");
                }
                out.add(secret ? Piece.secret(name, value) : Piece.value(name, value));
                use(name, found.layer().source(), secret, secret ? Secrets.mask(name) : value);
                return true;
            }

            List<Piece> inner = new ArrayList<>();
            Set<String> innerMissing = new LinkedHashSet<>();
            stack.push(name);
            boolean complete = expand(value, stack, inner, name, innerMissing);
            stack.pop();
            if (!complete) {
                // A secret's own text is never laid open, so a secret that could not be completed
                // is one hole. Any other variable keeps its shape, so the text around the hole can
                // still be read.
                if (secret) {
                    out.add(Piece.missing(name));
                } else {
                    out.addAll(inner);
                }
                missing.addAll(innerMissing);
                use(name, VariableSource.UNRESOLVED, secret, null);
                explanations.putIfAbsent(name, "It is defined in " + found.layer().label() + ", but its value needs "
                        + innerMissing.stream().map(n -> "{{" + n + "}}").collect(Collectors.joining(", ")) + ", which "
                        + (innerMissing.size() == 1 ? "has" : "have") + " no value.");
                return false;
            }
            if (secret) {
                out.add(Piece.secret(name, inner.stream().map(Piece::send).collect(Collectors.joining())));
                use(name, found.layer().source(), true, Secrets.mask(name));
            } else {
                out.addAll(inner);
                // The display form of what is inside, so a secret reached through this variable is masked here too.
                use(name, found.layer().source(), false, inner.stream().map(Piece::display).collect(Collectors.joining()));
            }
            return true;
        }

        /** The first layer that holds a value for the name. A blank value is not a value. */
        private Found find(String name) {
            for (VariableLayer layer : layers) {
                VariableLayer.Entry entry = layer.entries().get(name);
                if (entry != null && Texts.present(entry.value())) {
                    return new Found(layer, entry.value());
                }
            }
            return null;
        }

        private String blankReason(String name) {
            List<String> where = layers.stream().filter(layer -> layer.entries().containsKey(name)).map(VariableLayer::label).toList();
            return where.isEmpty() ? null : "It is defined in " + String.join(" and in ", where) + ", but its value is blank.";
        }

        /**
         * Reads a value from the operating system.
         *
         * @param name      the variable being resolved
         * @param pointer   the {@code env:} reference. The same as the name when the template
         *                  itself holds the reference
         * @param pointedAt the layer whose variable holds the reference, or null
         */
        private boolean operatingSystem(String name, String pointer, VariableLayer pointedAt, List<Piece> out, Set<String> missing) {
            String osName = pointer.substring(OS_REFERENCE.length()).strip();
            boolean indirect = !pointer.equals(name);
            String refusal = null;
            String value = null;
            if (osName.isEmpty()) {
                refusal = "It names no operating system variable.";
            } else if (!OS_NAME.matcher(osName).matches()) {
                refusal = "Only operating system variables whose names start with " + OS_NAME_PREFIX + " and hold nothing but letters, "
                        + "digits and underscores can be read, and " + osName + " is not one.";
            } else {
                value = operatingSystem.apply(osName);
                if (Texts.blank(value)) {
                    refusal = "The operating system variable " + osName + " is not set where this tool is running. Set it and restart "
                            + "the tool, or type a value for this run.";
                }
            }
            if (refusal != null) {
                String reason = indirect ? refusal + " It is read because {{" + name + "}} in " + pointedAt.label() + " points at it."
                        : refusal;
                if (indirect) {
                    use(name, VariableSource.UNRESOLVED, true, null);
                    explanations.putIfAbsent(name, "It is defined in " + pointedAt.label() + " as a reference to the operating system. "
                            + refusal);
                }
                out.add(Piece.missing(name));
                unresolved.add(pointer);
                missing.add(pointer);
                reasons.putIfAbsent(pointer, reason);
                explanations.putIfAbsent(pointer, reason);
                use(pointer, VariableSource.UNRESOLVED, true, null);
                return false;
            }
            // Always a secret, whatever the variable that points at it is called.
            out.add(Piece.secret(name, value));
            if (indirect) {
                use(name, VariableSource.OPERATING_SYSTEM, true, Secrets.mask(name));
            }
            use(pointer, VariableSource.OPERATING_SYSTEM, true, Secrets.mask(pointer));
            return true;
        }

        private boolean generated(String name, boolean secret, List<Piece> out, Set<String> missing) {
            String value = switch (name) {
                case "$guid", "$randomUUID" -> guids.get();
                case "$timestamp" -> String.valueOf(clock.instant().getEpochSecond());
                case "$isoTimestamp" -> ISO_MILLIS.format(clock.instant());
                default -> null;
            };
            if (value == null) {
                return missing(name, secret, out, missing, "Postman computes {{" + name + "}} itself and this tool does not. "
                        + "Type a value for it for this run.");
            }
            out.add(Piece.value(name, value));
            // One entry for every use, because every use has its own value.
            uses.add(new VariableUse(name, VariableSource.GENERATED, false, value));
            return true;
        }

        private boolean missing(String name, boolean secret, List<Piece> out, Set<String> missing, String reason) {
            unresolved.add(name);
            missing.add(name);
            if (reason != null) {
                reasons.putIfAbsent(name, reason);
            }
            explanations.putIfAbsent(name, reason != null ? reason : "No layer defines it.");
            out.add(Piece.missing(name));
            use(name, VariableSource.UNRESOLVED, secret, null);
            return false;
        }

        private void use(String name, VariableSource source, boolean secret, String display) {
            if (described.add(name)) {
                uses.add(new VariableUse(name, source, secret, display));
            }
        }

        /** The names from the first use of a variable round to its second, oldest first. */
        private static String chain(Deque<String> stack, String name) {
            List<String> oldestFirst = new ArrayList<>();
            for (Iterator<String> it = stack.descendingIterator(); it.hasNext(); ) {
                oldestFirst.add(it.next());
            }
            List<String> loop = new ArrayList<>(oldestFirst.subList(oldestFirst.indexOf(name), oldestFirst.size()));
            loop.add(name);
            return String.join(" -> ", loop);
        }
    }
}
