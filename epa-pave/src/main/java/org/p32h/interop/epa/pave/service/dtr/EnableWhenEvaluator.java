package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.dtr.ConditionState;
import org.p32h.interop.epa.pave.model.dtr.ItemEnabled;
import org.p32h.interop.epa.pave.model.dtr.QuestionRef;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson.Item;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * Evaluates the enableWhen conditions of a questionnaire against a set of answers, and from that
 * says which items are enabled, which questions are waiting for an answer and which required
 * questions have none.
 *
 * <p>It is a pure function of the questionnaire and the answers. It keeps nothing and calls nothing.
 *
 * <p>The rules, each of them the FHIR R4 definition read as written:
 * <ul>
 *   <li>{@code exists} holds when the question has an answer and the condition says true, or has
 *       none and the condition says false</li>
 *   <li>{@code =} and the ordering operators hold when any one answer satisfies them</li>
 *   <li>{@code !=} holds when no answer equals the value. A question with no answer therefore
 *       satisfies it. Some form renderers read this the other way. The literal reading is used
 *       because it is the safer one to be wrong with: it shows a question that could have stayed
 *       hidden, where the other reading would hide a question that may be required</li>
 *   <li>a question that is itself disabled is treated as having no answer</li>
 *   <li>an item in a disabled group is disabled</li>
 *   <li>several conditions combine by {@code enableBehavior}: all, or any</li>
 * </ul>
 *
 * <p>A condition this evaluator cannot evaluate is reported as not evaluated, with the reason. It
 * is never counted as true or as false. An item that such a condition decides is undetermined: it
 * is still shown, and when it is required and has no answer it still stops the response from
 * being complete.
 *
 * <p>Nothing but enableWhen and enableBehavior is evaluated. An item whose display, requiredness or
 * answer is decided by a rule this tool does not apply ({@link DtrNotApplied}), or that sits in a
 * group whose display is, is undetermined too, and differently: whether it still needs an answer is
 * not known at all, so it is never listed as a required question with no answer. So is an item whose
 * enableWhen reads a question that has no answer while such a rule fills that answer in: the
 * condition rests on an answer this tool does not work out. It is listed
 * apart, under {@link Form#undetermined()}, which a response that this tool completes must also
 * have empty.
 */
public final class EnableWhenEvaluator {

    private static final String DATE = "\\d{4}(-\\d{2}(-\\d{2})?)?";

    private EnableWhenEvaluator() {
    }

    /**
     * One condition of one item, evaluated.
     *
     * @param question    the linkId the condition looks at, or null when it names none
     * @param operator    the operator as written, or null when it states none
     * @param answerKey   the name of the property that holds the value to compare with, or null
     * @param explanation one plain sentence. It never quotes an answer: answers can be clinical
     */
    public record Condition(String question, String operator, String answerKey, ConditionState state, String explanation) {
    }

    /** One item with what the answers make of it. */
    public static final class ItemState {

        private final Item item;
        private final ItemEnabled enabled;
        private final String reason;
        private final List<Condition> conditions;
        private final List<JsonNode> given;
        private final List<DtrNotApplied.Rule> notApplied;

        private ItemState(Item item, ItemEnabled enabled, String reason, List<Condition> conditions, List<JsonNode> given,
                          List<DtrNotApplied.Rule> notApplied) {
            this.item = item;
            this.enabled = enabled;
            this.reason = reason;
            this.conditions = List.copyOf(conditions);
            this.given = List.copyOf(given);
            this.notApplied = List.copyOf(notApplied);
        }

        /**
         * The rules this tool does not apply that decide whether this item is shown, required or
         * answered: its own, and those of a group above it that decide whether the group is shown.
         * Empty when none does.
         */
        public List<DtrNotApplied.Rule> notApplied() {
            return notApplied;
        }

        /** True when a rule this tool does not apply decides whether the item still needs an answer. */
        public boolean judged() {
            return notApplied.isEmpty();
        }

        public Item item() {
            return item;
        }

        public ItemEnabled enabled() {
            return enabled;
        }

        /** One plain sentence saying why the item is enabled or not, or null for an item nothing conditions. */
        public String reason() {
            return reason;
        }

        public List<Condition> conditions() {
            return conditions;
        }

        /** The answers given to the item, whether or not it is enabled. */
        public List<JsonNode> given() {
            return given;
        }

        /** True when the item may appear in a response: it is enabled, or nothing could settle that it is not. */
        public boolean shown() {
            return enabled != ItemEnabled.DISABLED;
        }

        /** True when the item is shown and has at least one answer. */
        public boolean answered() {
            return shown() && !given.isEmpty();
        }
    }

    /** A questionnaire evaluated against one set of answers. */
    public static final class Form {

        private final List<Item> roots;
        private final Map<String, ItemState> byPath = new LinkedHashMap<>();

        private Form(List<Item> roots) {
            this.roots = roots;
        }

        public List<Item> roots() {
            return roots;
        }

        /** The state of the item at a path such as {@code item[0].item[1]}, or null when there is none. */
        public ItemState state(String path) {
            return byPath.get(path);
        }

        public ItemState state(Item item) {
            return byPath.get(item.path());
        }

        /** Every item in document order. */
        public List<ItemState> all() {
            return List.copyOf(byPath.values());
        }

        /** Every item that takes an answer, enabled or not. */
        public List<ItemState> questions() {
            return byPath.values().stream().filter(state -> state.item().question()).toList();
        }

        /** The questions that are shown. */
        public List<ItemState> shownQuestions() {
            return questions().stream().filter(ItemState::shown).toList();
        }

        /** The shown questions that have an answer. */
        public List<ItemState> answered() {
            return questions().stream().filter(ItemState::answered).toList();
        }

        /**
         * The shown questions still waiting for an answer. A question hidden by questionnaire-hidden,
         * on itself or on a group above it, is not asked of the person answering, so it is left out
         * here even though this tool does not apply that rule otherwise.
         */
        public List<QuestionRef> asked() {
            List<QuestionRef> out = new ArrayList<>();
            for (ItemState state : shownQuestions()) {
                if (state.given().isEmpty() && !DtrNotApplied.hides(state.notApplied())) {
                    out.add(ref(state, null));
                }
            }
            return out;
        }

        /**
         * Each required item that is shown and has no answer. A required group counts when no
         * question beneath it has one. A response is complete only when this is empty.
         */
        public List<QuestionRef> requiredUnanswered() {
            return unanswered(true);
        }

        /**
         * Each item with no answer that may be required and that this tool cannot judge, because a
         * rule it does not apply decides whether the item is shown, whether it is required or what
         * its answer is. Never listed among {@link #requiredUnanswered()}. A response this tool
         * completes itself must have this empty as well: one the service completed is the
         * service's to judge.
         */
        public List<QuestionRef> undetermined() {
            return unanswered(false);
        }

        private List<QuestionRef> unanswered(boolean judged) {
            List<QuestionRef> out = new ArrayList<>();
            for (ItemState state : byPath.values()) {
                Item item = state.item();
                boolean mayBeRequired = item.required() || state.notApplied().stream()
                        .anyMatch(rule -> rule.effect() == DtrNotApplied.Effect.REQUIRED);
                if (!state.shown() || !mayBeRequired || item.display()) {
                    continue;
                }
                List<Item> beneath = QuestionnaireJson.flatten(item.children()).stream().filter(Item::question).toList();
                // A group is judged only when every question that could answer it is.
                boolean itemJudged = state.judged() && (item.question() || beneath.stream().allMatch(child -> state(child).judged()));
                if (itemJudged != judged) {
                    continue;
                }
                boolean unanswered = item.question() ? state.given().isEmpty() : beneath.stream().noneMatch(child -> state(child).answered());
                if (!unanswered) {
                    continue;
                }
                if (!judged) {
                    out.add(new QuestionRef(item.ref().linkId(), item.ref().text(), "Not judged: " + (state.judged()
                            ? "a question beneath it is decided by a rule this tool does not apply" : "it is decided by "
                            + DtrNotApplied.names(state.notApplied()) + ", which this tool does not apply")
                            + ", so whether it still needs an answer is not known."));
                } else {
                    out.add(ref(state, item.question() ? null : "A required group with no answer beneath it."));
                }
            }
            return out;
        }

        /** The questions that were given an answer and are disabled, so their answers are left out of the response. */
        public List<QuestionRef> answersLeftOut() {
            List<QuestionRef> out = new ArrayList<>();
            for (ItemState state : questions()) {
                if (!state.shown() && !state.given().isEmpty()) {
                    out.add(state.item().ref());
                }
            }
            return out;
        }

        /** True when an item may appear in a response built from this evaluation. */
        public boolean include(Item item) {
            ItemState state = state(item);
            return state != null && state.shown();
        }

        private static QuestionRef ref(ItemState state, String note) {
            QuestionRef ref = state.item().ref();
            String why = state.enabled() == ItemEnabled.UNDETERMINED
                    ? "Whether this item is enabled could not be worked out, so it is treated as one that is shown." : note;
            return why == null ? ref : new QuestionRef(ref.linkId(), ref.text(), why);
        }
    }

    /**
     * @param questionnaire the Questionnaire resource
     * @param answers       the answers given, by linkId, each as a FHIR answer object
     */
    public static Form evaluate(JsonNode questionnaire, Map<String, List<JsonNode>> answers) {
        List<Item> roots = QuestionnaireJson.items(questionnaire);
        Run run = new Run(QuestionnaireJson.flatten(roots), answers == null ? Map.of() : answers);
        Form form = new Form(roots);
        for (Item item : run.items) {
            // Never null here. Only a call made while the same item is still being worked out returns null.
            form.byPath.put(item.path(), run.state(item));
        }
        return form;
    }

    /** What one comparison of one answer came to. A null result means it could not be made, and the reason says why. */
    private record Compared(Boolean result, String reason, boolean codeOnly) {

        static Compared of(boolean result) {
            return new Compared(result, null, false);
        }

        static Compared unknown(String reason) {
            return new Compared(null, reason, false);
        }
    }

    private static final class Run {

        private final List<Item> items;
        private final Map<String, List<JsonNode>> answers;
        private final Map<String, Item> byLinkId = new LinkedHashMap<>();
        private final Map<Item, ItemState> states = new IdentityHashMap<>();
        private final Set<Item> visiting = Collections.newSetFromMap(new IdentityHashMap<>());

        private Run(List<Item> items, Map<String, List<JsonNode>> answers) {
            this.items = items;
            this.answers = answers;
            for (Item item : items) {
                if (item.linkId() != null) {
                    byLinkId.putIfAbsent(item.linkId(), item);
                }
            }
        }

        private List<JsonNode> given(Item item) {
            return item.linkId() == null || !item.question() ? List.of() : answers.getOrDefault(item.linkId(), List.of());
        }

        /** The state of an item, or null when working it out came back round to the item itself. */
        private ItemState state(Item item) {
            ItemState done = states.get(item);
            if (done != null) {
                return done;
            }
            if (!visiting.add(item)) {
                return null;
            }
            try {
                ItemState parent = item.parent() == null ? null : state(item.parent());
                List<Condition> conditions = new ArrayList<>();
                List<DtrNotApplied.Rule> through = new ArrayList<>();
                for (JsonNode entry : JsonSafe.elements(item.node(), "enableWhen")) {
                    conditions.add(condition(entry, through));
                }
                ItemEnabled own = ItemEnabled.ENABLED;
                String reason = null;
                if (!conditions.isEmpty()) {
                    boolean anyTrue = conditions.stream().anyMatch(c -> c.state() == ConditionState.TRUE);
                    boolean anyFalse = conditions.stream().anyMatch(c -> c.state() == ConditionState.FALSE);
                    boolean anyUnknown = conditions.stream().anyMatch(c -> c.state() == ConditionState.NOT_EVALUATED);
                    String behavior = JsonSafe.presentText(item.node(), "enableBehavior");
                    boolean several = conditions.size() > 1;
                    if (!several || "all".equals(behavior)) {
                        own = anyFalse ? ItemEnabled.DISABLED : anyUnknown ? ItemEnabled.UNDETERMINED : ItemEnabled.ENABLED;
                    } else if ("any".equals(behavior)) {
                        own = anyTrue ? ItemEnabled.ENABLED : anyUnknown ? ItemEnabled.UNDETERMINED : ItemEnabled.DISABLED;
                    } else if (!anyFalse && !anyUnknown) {
                        // Every condition holds, so the item is enabled whichever way they were meant to combine.
                        own = ItemEnabled.ENABLED;
                    } else if (!anyTrue && !anyUnknown) {
                        own = ItemEnabled.DISABLED;
                    } else {
                        own = ItemEnabled.UNDETERMINED;
                        reason = "Undetermined: the item has several conditions that do not agree, and it states no enableBehavior of "
                                + "all or any to combine them by. It is still shown.";
                    }
                    if (reason == null) {
                        reason = switch (own) {
                            case ENABLED -> several ? ("any".equals(behavior) ? "Enabled: at least one of its conditions holds."
                                    : "Enabled: every one of its conditions holds.") : "Enabled: its condition holds.";
                            case DISABLED -> several ? ("any".equals(behavior) ? "Disabled: none of its conditions holds."
                                    : "Disabled: at least one of its conditions does not hold.") : "Disabled: its condition does not hold.";
                            case UNDETERMINED -> "Undetermined: a condition that decides it could not be evaluated. It is still shown.";
                        };
                    }
                }
                List<DtrNotApplied.Rule> rules = new ArrayList<>(DtrNotApplied.governing(item.node()));
                if (own == ItemEnabled.UNDETERMINED) {
                    // A condition that reads an answer a rule this tool does not apply would fill in decides nothing here, so neither
                    // does the item: whether it still needs an answer rests on that rule.
                    through.stream().filter(rule -> !rules.contains(rule)).forEach(rules::add);
                }
                List<DtrNotApplied.Rule> shownBy = rules.stream().filter(rule -> rule.effect() == DtrNotApplied.Effect.SHOWN).toList();
                if (!shownBy.isEmpty() && own != ItemEnabled.DISABLED) {
                    own = ItemEnabled.UNDETERMINED;
                    reason = "Undetermined: whether it is shown is decided by " + DtrNotApplied.names(shownBy) + ", which this tool does not "
                            + "apply. It is still shown, and it is never reported as a required question with no answer.";
                } else if (rules.size() > shownBy.size()) {
                    String decided = "Its " + (rules.stream().anyMatch(rule -> rule.effect() == DtrNotApplied.Effect.VALUE) ? "answer" : "being required")
                            + " is decided by " + DtrNotApplied.names(rules) + ", which this tool does not apply, so it is never reported as a "
                            + "required question with no answer.";
                    reason = reason == null ? decided : reason + " " + decided;
                }
                if (parent != null) {
                    // What decides whether a group is shown decides it for everything in the group.
                    parent.notApplied().stream().filter(rule -> rule.effect() == DtrNotApplied.Effect.SHOWN && !rules.contains(rule))
                            .forEach(rules::add);
                }
                ItemEnabled enabled = own;
                if (item.parent() != null) {
                    ItemEnabled above = parent == null ? ItemEnabled.UNDETERMINED : parent.enabled();
                    if (above == ItemEnabled.DISABLED) {
                        enabled = ItemEnabled.DISABLED;
                        reason = "Disabled: the group it sits in is disabled.";
                    } else if (above == ItemEnabled.UNDETERMINED && own != ItemEnabled.DISABLED) {
                        enabled = ItemEnabled.UNDETERMINED;
                        reason = "Undetermined: whether the group it sits in is enabled could not be worked out. It is still shown.";
                    }
                }
                ItemState state = new ItemState(item, enabled, reason, conditions, given(item), rules);
                states.put(item, state);
                return state;
            } finally {
                visiting.remove(item);
            }
        }

        /**
         * @param through receives, for a condition that could not be evaluated because the question
         *                it reads has no answer and a rule this tool does not apply fills its answer
         *                in, that rule as one that decides whether this item is shown
         */
        private Condition condition(JsonNode entry, List<DtrNotApplied.Rule> through) {
            String question = JsonSafe.presentText(entry, "question");
            String operator = JsonSafe.presentText(entry, "operator");
            String key = QuestionnaireJson.valueKey(entry, "answer");
            JsonNode expected = key == null ? MissingNode.getInstance() : entry.get(key);
            if (question == null) {
                return notEvaluated(question, operator, key, "the condition names no question to look at");
            }
            if (operator == null) {
                return notEvaluated(question, operator, key, "the condition states no operator");
            }
            if (!DtrCodes.inValueSet(DtrCodes.OPERATOR, operator)) {
                return notEvaluated(question, operator, key, "its operator is not one FHIR defines");
            }
            if (key == null || JsonSafe.nullOrMissing(expected)) {
                return notEvaluated(question, operator, key, "the condition states no value to compare with");
            }
            Item dependency = byLinkId.get(question);
            if (dependency == null) {
                return notEvaluated(question, operator, key, "the questionnaire has no item with the linkId it names");
            }
            ItemState looked = state(dependency);
            if (looked == null) {
                return notEvaluated(question, operator, key, "the condition depends on its own item, directly or through other conditions");
            }
            List<JsonNode> given = looked.given();
            String aside = "";
            if (looked.enabled() == ItemEnabled.DISABLED) {
                if (!given.isEmpty()) {
                    aside = " The question it looks at is itself disabled, so it is treated as having no answer.";
                }
                given = List.of();
            } else if (looked.enabled() == ItemEnabled.UNDETERMINED && !given.isEmpty()) {
                return notEvaluated(question, operator, key, "whether the question it looks at is enabled could not be worked out, so its "
                        + "answer cannot be relied on");
            } else if (given.isEmpty()) {
                List<DtrNotApplied.Rule> filling = DtrNotApplied.governing(dependency.node()).stream()
                        .filter(rule -> rule.effect() == DtrNotApplied.Effect.VALUE).toList();
                if (!filling.isEmpty()) {
                    through.add(DtrNotApplied.through(filling, question));
                    return notEvaluated(question, operator, key, "the question it looks at, " + question + ", has no answer here, and its "
                            + "answer is filled in by " + DtrNotApplied.names(filling) + ", which this tool does not apply");
                }
            }

            if ("exists".equals(operator)) {
                if (!expected.isBoolean()) {
                    return notEvaluated(question, operator, key, "the exists operator needs true or false to compare with");
                }
                boolean wanted = Boolean.TRUE.equals(JsonSafe.bool(expected));
                boolean holds = wanted != given.isEmpty();
                return new Condition(question, operator, key, holds ? ConditionState.TRUE : ConditionState.FALSE,
                        (holds ? "The condition holds: " : "The condition does not hold: ")
                                + (given.isEmpty() ? "the question it looks at has no answer." : "the question it looks at has an answer.") + aside);
            }

            boolean negated = "!=".equals(operator);
            boolean anyTrue = false;
            boolean codeOnly = false;
            String unknown = null;
            for (JsonNode answer : given) {
                Compared compared = compare(negated ? "=" : operator, key, expected, answer);
                if (compared.result() == null) {
                    unknown = unknown == null ? compared.reason() : unknown;
                } else if (compared.result()) {
                    anyTrue = true;
                    codeOnly |= compared.codeOnly();
                }
            }
            String codeNote = codeOnly ? " One side states no coding system, so only the codes were compared." : "";
            if (negated) {
                // The FHIR definition: true when no answer equals the value, which a question with no answer satisfies.
                if (anyTrue) {
                    return new Condition(question, operator, key, ConditionState.FALSE,
                            "The condition does not hold: an answer equals the value." + codeNote + aside);
                }
                if (unknown != null) {
                    return notEvaluated(question, operator, key, unknown);
                }
                return new Condition(question, operator, key, ConditionState.TRUE, (given.isEmpty()
                        ? "The condition holds: the question it looks at has no answer, so no answer equals the value."
                        : "The condition holds: no answer equals the value.") + aside);
            }
            if (anyTrue) {
                return new Condition(question, operator, key, ConditionState.TRUE, "The condition holds." + codeNote + aside);
            }
            if (unknown != null) {
                return notEvaluated(question, operator, key, unknown);
            }
            return new Condition(question, operator, key, ConditionState.FALSE, (given.isEmpty()
                    ? "The condition does not hold: the question it looks at has no answer." : "The condition does not hold.") + aside);
        }

        private static Condition notEvaluated(String question, String operator, String key, String why) {
            return new Condition(question, operator, key, ConditionState.NOT_EVALUATED, "Not evaluated: " + why + ".");
        }
    }

    /**
     * Compares one answer with the value of a condition.
     *
     * @param operator    any operator but exists and {@code !=}, which the caller works out from equality
     * @param expectedKey the property the condition's value sits under, such as {@code answerCoding}
     */
    private static Compared compare(String operator, String expectedKey, JsonNode expected, JsonNode answer) {
        String actualKey = QuestionnaireJson.valueKey(answer, "value");
        if (actualKey == null) {
            return Compared.unknown("an answer carries no value");
        }
        JsonNode actual = answer.get(actualKey);
        String expectedType = expectedKey.substring("answer".length());
        String actualType = actualKey.substring("value".length());
        boolean equality = "=".equals(operator);
        switch (expectedType) {
            case "Integer", "Decimal" -> {
                if (!actualType.equals("Integer") && !actualType.equals("Decimal")) {
                    return mismatch(expectedKey, actualKey);
                }
                BigDecimal wanted = number(expected);
                BigDecimal given = number(actual);
                if (wanted == null || given == null) {
                    return Compared.unknown("a value that should be a number is not one");
                }
                return Compared.of(ordered(operator, given.compareTo(wanted)));
            }
            case "Boolean" -> {
                if (!actualType.equals("Boolean")) {
                    return mismatch(expectedKey, actualKey);
                }
                if (!equality) {
                    return Compared.unknown("true and false have no order, so " + operator + " cannot be applied to them");
                }
                if (!expected.isBoolean() || !actual.isBoolean()) {
                    return Compared.unknown("a value that should be true or false is neither");
                }
                return Compared.of(JsonSafe.bool(expected).equals(JsonSafe.bool(actual)));
            }
            case "Date" -> {
                if (!actualType.equals("Date")) {
                    return mismatch(expectedKey, actualKey);
                }
                String wanted = JsonSafe.presentText(expected);
                String given = JsonSafe.presentText(actual);
                if (wanted == null || given == null || !wanted.matches(DATE) || !given.matches(DATE)) {
                    return Compared.unknown("a value that should be a date is not written as one");
                }
                if (wanted.length() != given.length()) {
                    return Compared.unknown("the two dates are not written to the same precision");
                }
                return Compared.of(ordered(operator, given.compareTo(wanted)));
            }
            case "DateTime" -> {
                if (!actualType.equals("DateTime")) {
                    return mismatch(expectedKey, actualKey);
                }
                return dateTimes(operator, JsonSafe.presentText(expected), JsonSafe.presentText(actual));
            }
            case "Time" -> {
                if (!actualType.equals("Time")) {
                    return mismatch(expectedKey, actualKey);
                }
                try {
                    LocalTime wanted = LocalTime.parse(String.valueOf(JsonSafe.presentText(expected)));
                    LocalTime given = LocalTime.parse(String.valueOf(JsonSafe.presentText(actual)));
                    return Compared.of(ordered(operator, given.compareTo(wanted)));
                } catch (DateTimeParseException e) {
                    return Compared.unknown("a value that should be a time of day is not written as one");
                }
            }
            case "String" -> {
                if (!actualType.equals("String")) {
                    return mismatch(expectedKey, actualKey);
                }
                if (!equality) {
                    return Compared.unknown("this tool does not put text in order, so " + operator + " cannot be applied to it");
                }
                String wanted = JsonSafe.text(expected);
                return wanted == null ? Compared.unknown("the condition's value is not text") : Compared.of(wanted.equals(JsonSafe.text(actual)));
            }
            case "Coding" -> {
                if (!actualType.equals("Coding")) {
                    return mismatch(expectedKey, actualKey);
                }
                if (!equality) {
                    return Compared.unknown("codings have no order, so " + operator + " cannot be applied to them");
                }
                String wantedCode = JsonSafe.presentText(expected, "code");
                if (wantedCode == null) {
                    return Compared.unknown("the coding the condition compares with has no code");
                }
                String wantedSystem = JsonSafe.presentText(expected, "system");
                String givenSystem = JsonSafe.presentText(actual, "system");
                boolean bothSystems = wantedSystem != null && givenSystem != null;
                boolean equal = wantedCode.equals(JsonSafe.presentText(actual, "code")) && (!bothSystems || wantedSystem.equals(givenSystem));
                return new Compared(equal, null, equal && !bothSystems);
            }
            case "Reference" -> {
                if (!actualType.equals("Reference")) {
                    return mismatch(expectedKey, actualKey);
                }
                if (!equality) {
                    return Compared.unknown("references have no order, so " + operator + " cannot be applied to them");
                }
                String wanted = JsonSafe.presentText(expected, "reference");
                return wanted == null ? Compared.unknown("the reference the condition compares with names nothing")
                        : Compared.of(wanted.equals(JsonSafe.presentText(actual, "reference")));
            }
            case "Quantity" -> {
                return Compared.unknown("this tool does not compare quantities, because that needs their units to be converted");
            }
            default -> {
                return Compared.unknown("the answer type " + expectedKey + " is not one this tool handles");
            }
        }
    }

    private static Compared mismatch(String expectedKey, String actualKey) {
        return Compared.unknown("the answer given is a " + actualKey + " and the condition compares with an " + expectedKey);
    }

    private static Compared dateTimes(String operator, String wanted, String given) {
        if (wanted == null || given == null) {
            return Compared.unknown("a value that should be a date and time is missing");
        }
        try {
            return Compared.of(ordered(operator, OffsetDateTime.parse(given).toInstant().compareTo(OffsetDateTime.parse(wanted).toInstant())));
        } catch (DateTimeParseException e) {
            // Without a time zone on both, or with only a date on both, the text decides when it is written to one precision.
            if (wanted.matches(DATE) && given.matches(DATE) && wanted.length() == given.length()) {
                return Compared.of(ordered(operator, given.compareTo(wanted)));
            }
            return Compared.unknown("the two date-times differ in precision or state no time zone, so this tool cannot compare them");
        }
    }

    private static boolean ordered(String operator, int comparison) {
        return switch (operator) {
            case "=" -> comparison == 0;
            case ">" -> comparison > 0;
            case "<" -> comparison < 0;
            case ">=" -> comparison >= 0;
            case "<=" -> comparison <= 0;
            default -> false;
        };
    }

    private static BigDecimal number(JsonNode node) {
        String text = JsonSafe.presentText(node);
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
