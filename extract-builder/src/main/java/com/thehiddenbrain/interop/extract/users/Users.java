package com.thehiddenbrain.interop.extract.users;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class Users {

    private final Map<String, Actor> users = new LinkedHashMap<>();

    public Users() {
        users.put("analyst", new Actor("analyst", "Jordan Rivera", "ANALYST", false, "Business analyst, Vendor Data Exchange"));
        users.put("analyst2", new Actor("analyst2", "Priya Okafor", "ANALYST", true, "Technical analyst, Claims Operations"));
        users.put("approver", new Actor("approver", "Dev Patel", "APPROVER", true, "Manager, Vendor Data Exchange"));
        users.put("admin", new Actor("admin", "Morgan Chen", "ADMIN", true, "Integration engineer"));
    }

    public Actor get(String id) {
        Actor a = users.get(id == null ? "analyst" : id);
        return a == null ? users.get("analyst") : a;
    }

    public List<Actor> all() {
        return List.copyOf(users.values());
    }
}
