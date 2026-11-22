package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;

/**
 * Reading and running the frontend's files, for the tests that hold the rules of the screens.
 *
 * <p>The build has no browser and no script engine of its own. A rule of the screens is held
 * either over the text of a file, or by running the module itself with Node where Node is
 * installed ({@link #runModule}), with a stand-in for the few parts of the browser's document the
 * helpers use ({@link #DOM_STAND_IN}). Where Node is not installed such a test is skipped and says
 * so; the rules read from the text still apply.
 */
final class StaticSources {

    /** The frontend, from the project directory the tests run in. */
    static final Path ROOT = Path.of("src/main/resources/static");

    static final Path JS = ROOT.resolve("js");

    private StaticSources() {
    }

    /** One file of the frontend: its path under the static directory, with forward slashes, and its text. */
    record Source(String name, String text) {
    }

    /** What a run of Node printed, standard output and error together, and its exit value. */
    record NodeRun(int exit, String output) {

        /** The printed lines, in order. */
        List<String> lines() {
            return output.lines().toList();
        }
    }

    /** Every file under the static directory with one of the extensions, sorted by path. */
    static List<Source> sources(String... extensions) throws IOException {
        assertThat(Files.isDirectory(ROOT)).as("the test runs from the project directory and the frontend exists").isTrue();
        List<Source> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = ROOT.relativize(file).toString().replace('\\', '/');
                if (Arrays.stream(extensions).anyMatch(name::endsWith)) {
                    found.add(new Source(name, Files.readString(file, StandardCharsets.UTF_8)));
                }
            }
        }
        return found;
    }

    /** The text of a file under the static directory, by its path there ("index.html", "css/pave.css"). */
    static String read(String name) throws IOException {
        return Files.readString(ROOT.resolve(name), StandardCharsets.UTF_8);
    }

    /** The text of a script under js/. */
    static String script(String name) throws IOException {
        return read("js/" + name);
    }

    /** The body of the first function or method named, up to its closing brace at the same indent. */
    static String body(String source, String opening) {
        int start = source.indexOf(opening);
        assertThat(start).as(opening).isGreaterThanOrEqualTo(0);
        int lineStart = source.lastIndexOf('\n', start) + 1;
        String indent = source.substring(lineStart, start);
        int end = source.indexOf("\n" + indent + "}", start);
        assertThat(end).as("end of " + opening).isGreaterThan(start);
        return source.substring(start, end);
    }

    /**
     * The text of a script without its comments: a block comment, and a line comment that starts a
     * line. Comments name files and members in words, which a rule over the code must not count.
     */
    static String withoutComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf("/*", at);
            if (open < 0) {
                out.append(text, at, text.length());
                break;
            }
            out.append(text, at, open).append(' ');
            int close = text.indexOf("*/", open + 2);
            at = close < 0 ? text.length() : close + 2;
        }
        List<String> kept = new ArrayList<>();
        for (String line : out.toString().split("\n", -1)) {
            kept.add(line.strip().startsWith("//") ? "" : line);
        }
        return String.join("\n", kept);
    }

    /** The Node executable on the path, or null when there is none. */
    static Path node() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            for (String name : List.of("node", "node.exe")) {
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Runs a script as an ES module with Node, in folder, beside a copy of every script of the
     * frontend under js/ and the stand-in for the document as browser.js. The script imports them
     * as './js/dom.js' and './browser.js'; browser.js is imported first by a script whose modules
     * touch the document. what names what is run, for the message when Node is not installed: the
     * test is then skipped, not passed.
     */
    static NodeRun runModule(Path folder, String what, String script) throws IOException, InterruptedException {
        Path node = node();
        Assumptions.assumeTrue(node != null, "Node is not installed here, so " + what + " was not run.");
        Path scripts = folder.resolve("js");
        Files.createDirectories(scripts);
        try (Stream<Path> files = Files.list(JS)) {
            for (Path file : files.filter(each -> each.toString().endsWith(".js")).toList()) {
                Files.copy(file, scripts.resolve(file.getFileName().toString()));
            }
        }
        Files.writeString(folder.resolve("package.json"), "{\"type\": \"module\"}\n", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("browser.js"), DOM_STAND_IN, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("run.js"), script, StandardCharsets.UTF_8);
        Path output = folder.resolve("output.txt");
        Process process = new ProcessBuilder(node.toString(), "run.js").directory(folder.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Node did not finish " + what + " within 60 seconds.");
        }
        return new NodeRun(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
    }

    /**
     * A stand-in for the few parts of the browser's document that dom.js and the modules built on
     * it use: elements with attributes, classes, children, text and listeners, the simple selectors
     * those modules query (a tag, .class, #id, [attribute], joined by spaces or listed with commas),
     * a dialog that opens and closes, and a clipboard that a test can make refuse. It is not a
     * browser: nothing is laid out and nothing is styled, and an event fires only when it is
     * dispatched. globalThis.standIn.clipboard holds what was copied.
     */
    static final String DOM_STAND_IN = """
            class StandInNode {
                constructor(nodeType) {
                    this.nodeType = nodeType;
                    this.childNodes = [];
                    this.parentNode = null;
                }
                get isConnected() {
                    let node = this;
                    while (node.parentNode) {
                        node = node.parentNode;
                    }
                    return node === globalThis.document.documentElement;
                }
                get textContent() {
                    let out = '';
                    const stack = [this];
                    while (stack.length > 0) {
                        const node = stack.pop();
                        if (node.nodeType === 3) {
                            out += node.data;
                        } else {
                            for (let i = node.childNodes.length - 1; i >= 0; i--) {
                                stack.push(node.childNodes[i]);
                            }
                        }
                    }
                    return out;
                }
                set textContent(value) {
                    this.replaceChildren();
                    const text = String(value);
                    if (text !== '') {
                        this.appendChild(new StandInText(text));
                    }
                }
                appendChild(node) {
                    if (node.nodeType === 11) {
                        for (const child of node.childNodes.slice()) {
                            this.appendChild(child);
                        }
                        return node;
                    }
                    if (node.parentNode) {
                        node.parentNode.removeChild(node);
                    }
                    node.parentNode = this;
                    this.childNodes.push(node);
                    return node;
                }
                removeChild(node) {
                    const at = this.childNodes.indexOf(node);
                    if (at >= 0) {
                        this.childNodes.splice(at, 1);
                        node.parentNode = null;
                    }
                    return node;
                }
                append(...nodes) {
                    for (const node of nodes) {
                        this.appendChild(typeof node === 'string' ? new StandInText(node) : node);
                    }
                }
                replaceChildren(...nodes) {
                    for (const child of this.childNodes) {
                        child.parentNode = null;
                    }
                    this.childNodes = [];
                    this.append(...nodes);
                }
                remove() {
                    if (this.parentNode) {
                        this.parentNode.removeChild(this);
                    }
                }
                contains(other) {
                    let node = other;
                    while (node) {
                        if (node === this) {
                            return true;
                        }
                        node = node.parentNode;
                    }
                    return false;
                }
            }

            class StandInText extends StandInNode {
                constructor(data) {
                    super(3);
                    this.data = String(data);
                }
                get textContent() {
                    return this.data;
                }
                set textContent(value) {
                    this.data = String(value);
                }
            }

            class StandInFragment extends StandInNode {
                constructor() {
                    super(11);
                }
            }

            function compound(text) {
                const part = {tag: null, id: null, classes: [], attributes: []};
                let at = 0;
                const word = () => {
                    let end = at;
                    while (end < text.length && '.#['.indexOf(text[end]) < 0) {
                        end++;
                    }
                    const found = text.slice(at, end);
                    at = end;
                    return found;
                };
                if (at < text.length && '.#['.indexOf(text[at]) < 0) {
                    part.tag = word().toLowerCase();
                }
                while (at < text.length) {
                    const mark = text[at];
                    at++;
                    if (mark === '.') {
                        part.classes.push(word());
                    } else if (mark === '#') {
                        part.id = word();
                    } else {
                        const end = text.indexOf(']', at);
                        const inside = text.slice(at, end);
                        at = end + 1;
                        const equals = inside.indexOf('=');
                        part.attributes.push(equals < 0 ? {name: inside, value: null}
                            : {name: inside.slice(0, equals), value: inside.slice(equals + 1).split('"').join('')});
                    }
                }
                return part;
            }

            function matchesCompound(element, part) {
                if (element.nodeType !== 1) {
                    return false;
                }
                if (part.tag && element.localName !== part.tag) {
                    return false;
                }
                if (part.id && element.id !== part.id) {
                    return false;
                }
                for (const name of part.classes) {
                    if (!element.classList.contains(name)) {
                        return false;
                    }
                }
                for (const attribute of part.attributes) {
                    const held = element.hasAttribute(attribute.name) || element[attribute.name] === true;
                    if (!held || (attribute.value !== null && element.getAttribute(attribute.name) !== attribute.value)) {
                        return false;
                    }
                }
                return true;
            }

            function matchesSelector(element, selector) {
                for (const one of selector.split(',')) {
                    const parts = one.trim().split(' ').filter(Boolean).map(compound);
                    if (parts.length === 0 || !matchesCompound(element, parts[parts.length - 1])) {
                        continue;
                    }
                    let wanted = parts.length - 2;
                    let node = element.parentNode;
                    while (wanted >= 0 && node) {
                        if (matchesCompound(node, parts[wanted])) {
                            wanted--;
                        }
                        node = node.parentNode;
                    }
                    if (wanted < 0) {
                        return true;
                    }
                }
                return false;
            }

            class StandInElement extends StandInNode {
                constructor(tag) {
                    super(1);
                    this.localName = String(tag).toLowerCase();
                    this.tagName = this.localName.toUpperCase();
                    this.attributeMap = new Map();
                    this.listeners = new Map();
                    this.dataset = {};
                    this.style = {setProperty() {}, removeProperty() {}};
                    this.className = '';
                    this.id = '';
                    this.hidden = false;
                    this.disabled = false;
                    this.openState = false;
                }
                get open() {
                    return this.openState;
                }
                set open(value) {
                    const next = Boolean(value);
                    if (next !== this.openState) {
                        this.openState = next;
                        this.dispatchEvent({type: 'toggle'});
                    }
                }
                get children() {
                    return this.childNodes.filter(node => node.nodeType === 1);
                }
                get classList() {
                    const element = this;
                    const names = () => element.className.split(' ').filter(Boolean);
                    return {
                        contains: name => names().includes(name),
                        add: (...added) => {
                            element.className = Array.from(new Set(names().concat(added))).join(' ');
                        },
                        remove: (...removed) => {
                            element.className = names().filter(name => !removed.includes(name)).join(' ');
                        },
                        toggle: (name, force) => {
                            const on = force === undefined ? !names().includes(name) : Boolean(force);
                            if (on) {
                                element.classList.add(name);
                            } else {
                                element.classList.remove(name);
                            }
                            return on;
                        }
                    };
                }
                setAttribute(name, value) {
                    if (name === 'class') {
                        this.className = String(value);
                    } else if (name === 'id') {
                        this.id = String(value);
                    } else {
                        this.attributeMap.set(name, String(value));
                    }
                }
                getAttribute(name) {
                    if (name === 'class') {
                        return this.className;
                    }
                    if (name === 'id') {
                        return this.id || null;
                    }
                    return this.attributeMap.has(name) ? this.attributeMap.get(name) : null;
                }
                hasAttribute(name) {
                    return this.getAttribute(name) !== null;
                }
                removeAttribute(name) {
                    this.attributeMap.delete(name);
                }
                addEventListener(type, listener) {
                    if (!this.listeners.has(type)) {
                        this.listeners.set(type, []);
                    }
                    this.listeners.get(type).push(listener);
                }
                removeEventListener(type, listener) {
                    const list = this.listeners.get(type) || [];
                    this.listeners.set(type, list.filter(each => each !== listener));
                }
                dispatchEvent(event) {
                    const fired = Object.assign({target: this, preventDefault() {}}, event);
                    const results = [];
                    for (const listener of (this.listeners.get(fired.type) || []).slice()) {
                        results.push(listener(fired));
                    }
                    return Promise.all(results);
                }
                click() {
                    return this.dispatchEvent({type: 'click'});
                }
                focus() {
                    globalThis.document.activeElement = this;
                }
                matches(selector) {
                    return matchesSelector(this, selector);
                }
                closest(selector) {
                    let node = this;
                    while (node && node.nodeType === 1) {
                        if (matchesSelector(node, selector)) {
                            return node;
                        }
                        node = node.parentNode;
                    }
                    return null;
                }
                querySelectorAll(selector) {
                    const found = [];
                    const stack = this.childNodes.slice().reverse();
                    while (stack.length > 0) {
                        const node = stack.pop();
                        if (node.nodeType === 1) {
                            if (matchesSelector(node, selector)) {
                                found.push(node);
                            }
                            for (let i = node.childNodes.length - 1; i >= 0; i--) {
                                stack.push(node.childNodes[i]);
                            }
                        }
                    }
                    return found;
                }
                querySelector(selector) {
                    const all = this.querySelectorAll(selector);
                    return all.length > 0 ? all[0] : null;
                }
                showModal() {
                    this.open = true;
                }
                close() {
                    if (this.open) {
                        this.open = false;
                        this.dispatchEvent({type: 'close'});
                    }
                }
            }

            const documentElement = new StandInElement('html');
            const body = new StandInElement('body');
            documentElement.appendChild(body);

            globalThis.Node = StandInNode;
            globalThis.Element = StandInElement;
            globalThis.document = {
                documentElement,
                body,
                activeElement: body,
                createElement: tag => new StandInElement(tag),
                createTextNode: text => new StandInText(text),
                createDocumentFragment: () => new StandInFragment(),
                getElementById: id => body.querySelector('#' + id),
                querySelector: selector => body.querySelector(selector),
                querySelectorAll: selector => body.querySelectorAll(selector)
            };
            globalThis.standIn = {clipboard: null, clipboardRefuses: false};
            Object.defineProperty(globalThis.navigator, 'clipboard', {
                configurable: true,
                value: {
                    writeText: async text => {
                        if (globalThis.standIn.clipboardRefuses) {
                            throw new Error('refused');
                        }
                        globalThis.standIn.clipboard = String(text);
                    }
                }
            });
            """;
}
