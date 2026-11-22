package org.p32h.interop.epa.pave.service.config;

import java.util.List;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.postman.PostmanEnvironment;

/**
 * One uploaded environment as the store holds it.
 *
 * @param id                  the id this tool gives the environment, derived from its name. Safe
 *                            in a url path and in a file name, which the name itself may not be
 * @param name                the environment name, which is its identity: uploading another file
 *                            with the same name replaces this one
 * @param kind                sandbox, uat or prod, as declared at upload. Never inferred
 * @param content             the parsed export
 * @param file                where the uploaded file sits, relative to the store directory
 * @param originalFileName    the name the file was uploaded under, for display only
 * @param uploadedAt          when it was uploaded, as an ISO instant
 * @param acknowledgedNotProd true when the uploader confirmed that this environment, declared as
 *                            something other than production, is not production. Required when
 *                            the upload reads as production, and kept whenever it is given. A
 *                            write to a url that reads as production is sent under this
 *                            environment only when this is true
 * @param productionSigns     what about the upload looked like production, in words. Empty when
 *                            nothing did
 * @param settings            what the environment states for itself on screen (plan 5.1), never
 *                            null; {@link EnvironmentSettings#none()} when it states nothing or
 *                            when its stored settings could not be read
 * @param settingsProblem     why the stored settings could not be read, in words, or null when
 *                            they were read or there were none
 * @param unreadSettings      the settings entry exactly as store.json held it when it could not be
 *                            read, written back unchanged on every commit so nothing the tester
 *                            stated is lost; null otherwise
 */
record StoredEnvironment(String id, String name, EnvironmentKind kind, PostmanEnvironment content, String file,
                         String originalFileName, String uploadedAt, boolean acknowledgedNotProd, List<String> productionSigns,
                         EnvironmentSettings settings, String settingsProblem, tools.jackson.databind.JsonNode unreadSettings) {

    StoredEnvironment {
        productionSigns = productionSigns == null ? List.of() : List.copyOf(productionSigns);
        settings = settings == null ? EnvironmentSettings.none() : settings;
    }

    /** An environment that states no settings, as every environment of a version 1 store reads. */
    StoredEnvironment(String id, String name, EnvironmentKind kind, PostmanEnvironment content, String file, String originalFileName,
                      String uploadedAt, boolean acknowledgedNotProd, List<String> productionSigns) {
        this(id, name, kind, content, file, originalFileName, uploadedAt, acknowledgedNotProd, productionSigns, null, null, null);
    }

    /** The same environment with settings that were read or saved: any unreadable entry it held is replaced. */
    StoredEnvironment withSettings(EnvironmentSettings newSettings) {
        return new StoredEnvironment(id, name, kind, content, file, originalFileName, uploadedAt, acknowledgedNotProd, productionSigns,
                newSettings, null, null);
    }

    /** The same settings, readable or not, carried onto an environment uploaded again under the same name. */
    StoredEnvironment withSettingsOf(StoredEnvironment previous) {
        if (previous == null) {
            return this;
        }
        return new StoredEnvironment(id, name, kind, content, file, originalFileName, uploadedAt, acknowledgedNotProd, productionSigns,
                previous.settings, previous.settingsProblem, previous.unreadSettings);
    }

    EnvironmentRef ref() {
        return new EnvironmentRef(name, kind);
    }

    /** Prints the name and the kind. The content holds variable values and the settings secrets, which can be credentials. */
    @Override
    public String toString() {
        return "StoredEnvironment[" + name + ", " + kind + "]";
    }
}
