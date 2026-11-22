package org.p32h.interop.epa.pave.client.support;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.SignInSettings;
import tools.jackson.databind.JsonNode;

/**
 * The sentence of a token endpoint's answer that gave no token, with the endpoint's own reason
 * when it gave one, and the advice that follows from that reason (D-305).
 *
 * <p>An OAuth token endpoint says why it refused in {@code error} and {@code error_description}.
 * Without them a tester cannot tell a wrong secret from credentials sent in the wrong place, which
 * is what the operator met on the first real run. The endpoint's text is not trusted: only those
 * two fields are read, only as text, with control and format characters taken out, every secret
 * this sign-in holds replaced as the wire view replaces one a service repeats back (D-280), and cut
 * at {@link #MOST} characters. It reaches the screen and nothing else: the token service logs the
 * outcome only.
 */
public final class TokenRefusal {

    /** How much of each field is shown. A description longer than this is a page, not a reason. */
    static final int MOST = 300;

    /** Said after a field that was cut, so a shortened reason is not read as the whole of it. */
    static final String CUT = " ... (cut at " + MOST + " characters)";

    /** Every sentence of a refusal with a status begins so; {@link #advice} reads it back from here. */
    static final String ANSWERED = "No access token: the token endpoint answered HTTP ";

    static final String INVALID_CLIENT = "invalid_client";
    static final String INVALID_SCOPE = "invalid_scope";

    private TokenRefusal() {
    }

    /**
     * The sentence for an answer whose status is not a success, without the closing words about
     * the request, which the token service adds.
     *
     * @param secrets      the secrets this sign-in holds, each with its mask
     * @param clientSecret the client secret sent, or null; checked on its own as well, because a
     *                     secret too short for {@link SentSecrets} to search is still never shown
     */
    static String sentence(int status, String body, SentSecrets secrets, String clientSecret) {
        String start = ANSWERED + status;
        JsonNode answer = reason(body);
        String error = answer == null ? null : shown(answer.get("error"), secrets, clientSecret);
        if (error == null) {
            return start + ".";
        }
        String description = shown(answer.get("error_description"), secrets, clientSecret);
        if (description == null) {
            return start + " (" + error + ").";
        }
        return start + " (" + error + "). It said: " + description + (endsAsASentence(description) ? "" : ".");
    }

    /** The parsed answer when it is a JSON object with a text {@code error}, else null. */
    private static JsonNode reason(String body) {
        if (Texts.blank(body)) {
            return null;
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(body);
        if (!parsed.ok() || !JsonSafe.isObject(parsed.node())) {
            return null;
        }
        JsonNode error = parsed.node().get("error");
        return error != null && error.isString() ? parsed.node() : null;
    }

    /** One field as it may be shown, or null when it is absent, not a text or nothing is left of it. */
    private static String shown(JsonNode field, SentSecrets secrets, String clientSecret) {
        if (field == null || !field.isString()) {
            return null;
        }
        // Masked before and after the control characters go: a secret written whole is found as
        // it came, and one split by a control character is found once it is joined again.
        String text = secrets.mask(field.stringValue()).text();
        text = secrets.mask(plain(text)).text();
        if (Texts.present(clientSecret) && text.contains(clientSecret.strip())) {
            // Only a secret too short to search for can still be here; the field is not shown at all rather than guessed around.
            return "(not shown: it holds the client secret)";
        }
        text = text.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > MOST) {
            int end = MOST;
            // Never half of a character that takes two chars.
            if (Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            text = text.substring(0, end).strip() + CUT;
        }
        return text;
    }

    /**
     * The text with line breaks and tabs made single spaces and every other control or format
     * character, and every half of a character, taken out, so the service cannot draw on the
     * screen or turn the text round.
     */
    static String plain(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        int at = 0;
        while (at < text.length()) {
            int point = text.codePointAt(at);
            at += Character.charCount(point);
            if (point == '\n' || point == '\r' || point == '\t' || Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = true;
                continue;
            }
            int type = Character.getType(point);
            if (Character.isISOControl(point) || type == Character.FORMAT || type == Character.SURROGATE || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                continue;
            }
            if (space && out.length() > 0) {
                out.append(' ');
            }
            space = false;
            out.appendCodePoint(point);
        }
        return out.toString();
    }

    private static boolean endsAsASentence(String text) {
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == ')';
    }

    /**
     * What to do next beyond checking the settings, from the sentence this class wrote, or null
     * when the answer points at no one setting. After an {@code invalid_client}, or a 401 that gave
     * no reason, it says where the client id and secret were sent and how to send them the other
     * way; after an {@code invalid_scope}, it points at Scope.
     *
     * <p>Read back from the sentence because the sentence is what a call that was not sent carries
     * to the run card; it is read only at the start, which this class wrote, never in the service's
     * words after "It said".
     *
     * @param failure the token service's sentence
     * @param how     how the environment's sign-in sends the client id and secret
     */
    public static String advice(String failure, SignInSettings.ClientAuthentication how) {
        if (failure == null || !failure.startsWith(ANSWERED) || failure.length() < ANSWERED.length() + 4) {
            return null;
        }
        String status = failure.substring(ANSWERED.length(), ANSWERED.length() + 3);
        String after = failure.substring(ANSWERED.length() + 3);
        String error = null;
        if (after.startsWith(" (")) {
            int close = after.indexOf(')');
            error = close < 0 ? null : after.substring(2, close);
        } else if (!after.startsWith(".")) {
            return null;
        }
        boolean refusedClient = INVALID_CLIENT.equals(error) && (status.equals("400") || status.equals("401"))
                || error == null && status.equals("401");
        // Each advice is one sentence: the error panel shows the action's first sentence and one more without a click, and folds
        // anything longer under "Why", where the one thing to change would be missed.
        if (refusedClient) {
            return how == SignInSettings.ClientAuthentication.BODY
                    ? "The client id and secret were sent in the form body: if the service wants them in a Basic header, choose Basic "
                    + "header under Client authentication in Configuration > Environments > Sign-in."
                    : "The client id and secret were sent in a Basic header: if the service wants them in the form body, choose In the "
                    + "form body under Client authentication in Configuration > Environments > Sign-in.";
        }
        if (INVALID_SCOPE.equals(error)) {
            return "The token endpoint refused the scope: check Scope under Sign-in in Configuration > Environments, which PAVE sends "
                    + "as it stands there, and not at all when it is empty.";
        }
        return null;
    }
}
