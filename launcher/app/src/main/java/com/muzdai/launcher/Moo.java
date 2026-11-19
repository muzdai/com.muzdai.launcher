package com.muzdai.launcher;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * One message of Roon's extension protocol ("MOO/1"), as it goes over the core's WebSocket at ws://core:9330/api:
 * a first line "MOO/1 VERB name", headers, an empty line and an optional JSON body. Verbs: REQUEST (either side
 * asks), CONTINUE (an answer, more to come: subscriptions, registering) and COMPLETE (the last answer). Pure, tested
 * on the JVM.
 */
final class Moo {
    final String verb, name;
    final int requestId;
    /** The JSON body, or null when there is none. */
    final JSONObject body;

    Moo(String verb, String name, int requestId, JSONObject body) {
        this.verb = verb;
        this.name = name;
        this.requestId = requestId;
        this.body = body;
    }

    static byte[] request(int requestId, String name, JSONObject body) {
        return format("REQUEST", name, requestId, body);
    }

    static byte[] format(String verb, String name, int requestId, JSONObject body) {
        StringBuilder head = new StringBuilder("MOO/1 ").append(verb).append(' ').append(name).append('\n')
                .append("Request-Id: ").append(requestId).append('\n');
        byte[] payload = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
        if (body != null) {
            head.append("Content-Type: application/json\nContent-Length: ").append(payload.length).append('\n');
        }
        head.append('\n');
        byte[] h = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[h.length + payload.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(payload, 0, out, h.length, payload.length);
        return out;
    }

    /** The message, or null when it is not one. */
    static Moo parse(byte[] raw) {
        String s = new String(raw, StandardCharsets.UTF_8);
        int end = s.indexOf("\n\n");
        String head = end < 0 ? s : s.substring(0, end);
        String rest = end < 0 ? "" : s.substring(end + 2);
        String[] lines = head.split("\n");
        String[] first = lines[0].split(" ", 3);
        if (first.length < 3 || !first[0].startsWith("MOO/")) return null;
        int id = -1;
        String type = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(": ");
            if (colon < 0) continue;
            String k = lines[i].substring(0, colon), v = lines[i].substring(colon + 2).trim();
            if ("Request-Id".equalsIgnoreCase(k)) {
                try {
                    id = Integer.parseInt(v);
                } catch (NumberFormatException ignored) {
                    // not a number: no id
                }
            } else if ("Content-Type".equalsIgnoreCase(k)) {
                type = v;
            }
        }
        JSONObject body = null;
        if (type != null && type.startsWith("application/json") && !rest.trim().isEmpty()) {
            try {
                body = new JSONObject(rest);
            } catch (JSONException ignored) {
                // a body that is not JSON: none
            }
        }
        return new Moo(first[1], first[2], id, body);
    }
}
