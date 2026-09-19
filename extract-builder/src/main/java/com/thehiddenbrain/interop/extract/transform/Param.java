package com.thehiddenbrain.interop.extract.transform;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One parameter of a rule, described well enough for the browser to render the form: kind drives the control
 * (text, int, boolean, enum, map grid, element picker, lookup picker, token picker), options fill an enum.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Param(String name, String label, String kind, boolean required, Object defaultValue, List<String> options, String help, String showWhen) {

    public static Param text(String name, String label, String help) { return new Param(name, label, "text", false, null, null, help, null); }
    public static Param required(String name, String label, String kind, String help) { return new Param(name, label, kind, true, null, null, help, null); }
    public static Param integer(String name, String label, Object def, String help) { return new Param(name, label, "int", false, def, null, help, null); }
    public static Param bool(String name, String label, boolean def, String help) { return new Param(name, label, "boolean", false, def, null, help, null); }
    public static Param choice(String name, String label, List<String> options, String def, String help) { return new Param(name, label, "enum", false, def, options, help, null); }
    public static Param of(String name, String label, String kind, String help) { return new Param(name, label, kind, false, null, null, help, null); }
    public Param when(String showWhen) { return new Param(name, label, kind, required, defaultValue, options, help, showWhen); }
}
