package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;

import java.util.function.Function;

/** A check defined inline with a lambda. */
public final class SimpleCheck implements Check {

    private final String id;
    private final String group;
    private final String title;
    private final Severity severity;
    private final String description;
    private final String citation;
    private final boolean needsPatient;
    private final Function<CheckContext, CheckResult> body;

    /** A check body that receives a result builder for its own check (so lambdas need not re-create the bean). */
    @FunctionalInterface
    public interface Body {
        CheckResult run(CheckResult.Builder result, CheckContext ctx);
    }

    public SimpleCheck(String id, String group, String title, Severity severity, String description, String citation, boolean needsPatient,
                       Function<CheckContext, CheckResult> body) {
        this.id = id;
        this.group = group;
        this.title = title;
        this.severity = severity;
        this.description = description;
        this.citation = citation;
        this.needsPatient = needsPatient;
        this.body = body;
    }

    /** Same as the constructor, but the body gets a builder bound to the check it belongs to. */
    public static SimpleCheck of(String id, String group, String title, Severity severity, String description, String citation,
                                 boolean needsPatient, Body body) {
        SimpleCheck[] self = new SimpleCheck[1];
        self[0] = new SimpleCheck(id, group, title, severity, description, citation, needsPatient, ctx -> body.run(self[0].result(), ctx));
        return self[0];
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String group() {
        return group;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Severity severity() {
        return severity;
    }

    @Override
    public String citation() {
        return citation;
    }

    @Override
    public boolean needsPatient() {
        return needsPatient;
    }

    @Override
    public CheckResult run(CheckContext ctx) {
        return body.apply(ctx);
    }

    /** Builder for results of this check (avoids re-creating the bean inside the lambda). */
    public CheckResult.Builder result() {
        return CheckResult.builder(this);
    }
}
