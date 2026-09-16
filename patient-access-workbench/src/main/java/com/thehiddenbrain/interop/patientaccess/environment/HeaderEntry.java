package com.thehiddenbrain.interop.patientaccess.environment;

import com.thehiddenbrain.interop.patientaccess.secrets.Secret;

/** An extra HTTP header sent with every request to the environment (e.g. an API-management subscription key). */
public record HeaderEntry(String name, String value, Secret secretValue, boolean secret) {
}
