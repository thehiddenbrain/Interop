package com.thehiddenbrain.interop.extract.api;

import com.thehiddenbrain.interop.extract.users.Actor;
import com.thehiddenbrain.interop.extract.users.Users;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ApiSupport {

    public static final String USER_HEADER = "X-Demo-User";

    private final Users users;

    public ApiSupport(Users users) {
        this.users = users;
    }

    public Actor actor(String header) {
        return users.get(header);
    }

    public static Map<String, Object> ok(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", message);
        return m;
    }
}
