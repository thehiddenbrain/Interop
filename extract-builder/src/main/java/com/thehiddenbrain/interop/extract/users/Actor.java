package com.thehiddenbrain.interop.extract.users;

import com.thehiddenbrain.interop.extract.config.ApiErrors;

import java.util.Arrays;

/**
 * The signed-in person for a request. The demo has no login: a header names one of three demo users, and the
 * browser's user switcher sets it. In production this comes from the corporate identity provider.
 */
public record Actor(String id, String name, String role, boolean phiUnmasked, String title) {

    public static final Actor SYSTEM = new Actor("system", "Scheduler", "SYSTEM", false, "Automated");

    public boolean is(String... roles) {
        return Arrays.asList(roles).contains(role);
    }

    public void require(String what, String... roles) {
        if (!is(roles) && !"SYSTEM".equals(role)) {
            throw new ApiErrors.Forbidden(name + " (" + roleLabel() + ") cannot " + what + ". Needs " + String.join(" or ", roles).toLowerCase() + ".");
        }
    }

    public String roleLabel() {
        return role.charAt(0) + role.substring(1).toLowerCase();
    }
}
