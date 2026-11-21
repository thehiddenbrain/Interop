package org.p32h.interop.epa.pave.postman;

import java.util.List;

/**
 * One request of an uploaded collection, wherever in the folder tree it sat.
 *
 * @param id            the id this tool gives the request. It is derived from the folder path and
 *                      the name, so the same file uploaded twice gives the same ids and an
 *                      operator's mapping correction survives a re-upload
 * @param name          the request name as the vendor wrote it
 * @param folderPath    the names of the folders the request sits in, outermost first. Empty for a
 *                      request at the top level
 * @param method        the HTTP method, upper case. GET when the file states none, which is what
 *                      Postman sends for such a request
 * @param url           the url exactly as written, placeholders intact, or null when the request has none
 * @param pathVariables the values Postman saved for {@code :name} path variables. They are shown
 *                      and never sent: the value of a path variable comes from the call
 * @param headers       every header, disabled ones included
 * @param bodyMode      Postman's body mode, for example raw or urlencoded, or null when there is no body
 * @param body          the raw body text exactly as written, or null when the request has no raw body
 * @param bodyLanguage  the language Postman recorded for a raw body, for example json, or null
 * @param scripted      true when a pre-request script would run before this request in Postman:
 *                      its own, or one inherited from an ancestor folder or the collection
 * @param scripts       every script Postman would run around this request, with where it sits
 * @param auth          the authentication helper Postman would apply, own or inherited, or null
 * @param examples      the saved example responses
 * @param description   the description text, or null
 * @param notes         what the parser set aside or had to decide for this request, in words
 * @param formCredentials the names of the fields of a body in Postman's urlencoded or formdata
 *                      mode that hold a credential typed into the file. Such a body is not loaded,
 *                      and only the names are kept, never a value
 */
public record PostmanRequest(String id, String name, List<String> folderPath, String method, String url,
                             List<PathVariable> pathVariables, List<PostmanHeader> headers, String bodyMode, String body,
                             String bodyLanguage, boolean scripted, List<PostmanScript> scripts, PostmanAuth auth,
                             List<PostmanExample> examples, String description, List<String> notes, List<String> formCredentials) {

    /** A value Postman saved for a {@code :name} segment of the url. */
    public record PathVariable(String name, String value) {
    }

    public PostmanRequest {
        folderPath = folderPath == null ? List.of() : List.copyOf(folderPath);
        pathVariables = pathVariables == null ? List.of() : List.copyOf(pathVariables);
        headers = headers == null ? List.of() : List.copyOf(headers);
        scripts = scripts == null ? List.of() : List.copyOf(scripts);
        examples = examples == null ? List.of() : List.copyOf(examples);
        notes = notes == null ? List.of() : List.copyOf(notes);
        formCredentials = formCredentials == null ? List.of() : List.copyOf(formCredentials);
    }

    /** True when a script would run after the response in Postman. It may store a value a later request needs. */
    public boolean testScripted() {
        return scripts.stream().anyMatch(script -> !script.preRequest());
    }

    /** The headers Postman would send: the ones that are not disabled. */
    public List<PostmanHeader> sentHeaders() {
        return headers.stream().filter(header -> !header.disabled()).toList();
    }

    /** The folder path and the name as one line, for messages that must say which request is meant. */
    public String qualifiedName() {
        return folderPath.isEmpty() ? name : String.join(" / ", folderPath) + " / " + name;
    }

    /** As {@link #qualifiedName()}, for a sentence: each name of a folder and of the request as {@link Names#shown(String)} quotes it. */
    public String quotedName() {
        return Names.path(folderPath, name);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.id = id;
        builder.name = name;
        builder.folderPath = folderPath;
        builder.method = method;
        builder.url = url;
        builder.pathVariables = pathVariables;
        builder.headers = headers;
        builder.bodyMode = bodyMode;
        builder.body = body;
        builder.bodyLanguage = bodyLanguage;
        builder.scripted = scripted;
        builder.scripts = scripts;
        builder.auth = auth;
        builder.examples = examples;
        builder.description = description;
        builder.notes = notes;
        builder.formCredentials = formCredentials;
        return builder;
    }

    /** Prints the id, the method and the name. Never a header value or a body, which can hold a credential or sample member data. */
    @Override
    public String toString() {
        return "PostmanRequest[" + id + ", " + method + " " + name + "]";
    }

    public static final class Builder {
        private String id;
        private String name;
        private List<String> folderPath = List.of();
        private String method;
        private String url;
        private List<PathVariable> pathVariables = List.of();
        private List<PostmanHeader> headers = List.of();
        private String bodyMode;
        private String body;
        private String bodyLanguage;
        private boolean scripted;
        private List<PostmanScript> scripts = List.of();
        private PostmanAuth auth;
        private List<PostmanExample> examples = List.of();
        private String description;
        private List<String> notes = List.of();
        private List<String> formCredentials = List.of();

        private Builder() {
        }

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder folderPath(List<String> value) {
            this.folderPath = value;
            return this;
        }

        public Builder method(String value) {
            this.method = value;
            return this;
        }

        public Builder url(String value) {
            this.url = value;
            return this;
        }

        public Builder pathVariables(List<PathVariable> value) {
            this.pathVariables = value;
            return this;
        }

        public Builder headers(List<PostmanHeader> value) {
            this.headers = value;
            return this;
        }

        public Builder bodyMode(String value) {
            this.bodyMode = value;
            return this;
        }

        public Builder body(String value) {
            this.body = value;
            return this;
        }

        public Builder bodyLanguage(String value) {
            this.bodyLanguage = value;
            return this;
        }

        public Builder scripted(boolean value) {
            this.scripted = value;
            return this;
        }

        public Builder scripts(List<PostmanScript> value) {
            this.scripts = value;
            return this;
        }

        public Builder auth(PostmanAuth value) {
            this.auth = value;
            return this;
        }

        public Builder examples(List<PostmanExample> value) {
            this.examples = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder notes(List<String> value) {
            this.notes = value;
            return this;
        }

        public Builder formCredentials(List<String> value) {
            this.formCredentials = value;
            return this;
        }

        public PostmanRequest build() {
            return new PostmanRequest(id, name, folderPath, method, url, pathVariables, headers, bodyMode, body, bodyLanguage, scripted,
                    scripts, auth, examples, description, notes, formCredentials);
        }
    }
}
