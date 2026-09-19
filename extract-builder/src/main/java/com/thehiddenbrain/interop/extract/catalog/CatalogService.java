package com.thehiddenbrain.interop.extract.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thehiddenbrain.interop.extract.catalog.CatalogModel.*;
import com.thehiddenbrain.interop.extract.config.ApiErrors;
import com.thehiddenbrain.interop.extract.config.AppProperties;
import com.thehiddenbrain.interop.extract.store.Ids;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.*;

/** Loads the catalog once and answers "what is element X, which entity, which join path reaches it". */
@Service
public class CatalogService {

    private final AppProperties props;
    private final ObjectMapper mapper;
    private Catalog catalog;
    private Map<String, Element> elements;
    private Map<String, Entity> entities;
    private Map<String, JoinPath> joinPaths;
    private Map<String, Lookup> lookups;
    private Map<String, FilterTemplate> templates;
    private Map<String, SubjectArea> subjectAreas;
    private String checksum;

    public CatalogService(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        reload();
    }

    public synchronized void reload() {
        try {
            byte[] bytes = Files.readAllBytes(props.catalogFile());
            catalog = mapper.readValue(bytes, Catalog.class);
            checksum = Ids.sha256(bytes).substring(0, 12);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read catalog " + props.catalogFile(), e);
        }
        elements = new LinkedHashMap<>();
        for (Element e : catalog.elements()) elements.put(e.id(), e);
        entities = new LinkedHashMap<>();
        for (Entity e : catalog.entities()) entities.put(e.id(), e);
        joinPaths = new LinkedHashMap<>();
        for (JoinPath j : catalog.joinPaths()) joinPaths.put(j.id(), j);
        lookups = new LinkedHashMap<>();
        for (Lookup l : catalog.lookups()) lookups.put(l.id(), l);
        templates = new LinkedHashMap<>();
        for (FilterTemplate t : catalog.filterTemplates()) templates.put(t.id(), t);
        subjectAreas = new LinkedHashMap<>();
        for (SubjectArea s : catalog.subjectAreas()) subjectAreas.put(s.id(), s);
    }

    public Catalog catalog() { return catalog; }
    public String checksum() { return checksum; }
    public String version() { return catalog.version(); }

    public Element element(String id) {
        Element e = elements.get(id);
        if (e == null) throw new ApiErrors.BadRequest("Unknown catalog element: " + id);
        return e;
    }

    public Optional<Element> findElement(String id) { return Optional.ofNullable(elements.get(id)); }
    public Collection<Element> elements() { return elements.values(); }
    public List<Element> elementsOf(String entityId) { return elements.values().stream().filter(e -> e.entity().equals(entityId)).toList(); }

    public Entity entity(String id) {
        Entity e = entities.get(id);
        if (e == null) throw new ApiErrors.BadRequest("Unknown catalog entity: " + id);
        return e;
    }

    public Collection<Entity> entities() { return entities.values(); }
    public Collection<JoinPath> joinPaths() { return joinPaths.values(); }
    public Optional<JoinPath> joinPath(String id) { return Optional.ofNullable(joinPaths.get(id)); }

    /** The direct join path from {@code from} to {@code to}, if the catalog declares one. */
    public Optional<JoinPath> joinPath(String from, String to) {
        return joinPaths.values().stream().filter(j -> j.from().equals(from) && j.to().equals(to)).findFirst();
    }

    public List<JoinPath> joinPathsFrom(String from) {
        return joinPaths.values().stream().filter(j -> j.from().equals(from)).toList();
    }

    public Lookup lookup(String id) {
        Lookup l = lookups.get(id);
        if (l == null) throw new ApiErrors.BadRequest("Unknown lookup: " + id);
        return l;
    }

    public Collection<Lookup> lookups() { return lookups.values(); }

    public FilterTemplate template(String id) {
        FilterTemplate t = templates.get(id);
        if (t == null) throw new ApiErrors.BadRequest("Unknown filter template: " + id);
        return t;
    }

    public Collection<FilterTemplate> templates() { return templates.values(); }

    public SubjectArea subjectArea(String id) {
        SubjectArea s = subjectAreas.get(id);
        if (s == null) throw new ApiErrors.BadRequest("Unknown subject area: " + id);
        return s;
    }

    public Collection<SubjectArea> subjectAreas() { return subjectAreas.values(); }

    /** Entities reachable from a root entity through declared join paths (one hop, plus "via" hops). */
    public List<String> reachableEntities(String root) {
        List<String> out = new ArrayList<>();
        out.add(root);
        for (JoinPath j : joinPathsFrom(root)) if (!out.contains(j.to())) out.add(j.to());
        return out;
    }

    /** Element type as declared, defaulting to STRING for unknown ids. */
    public String typeOf(String elementId) {
        return findElement(elementId).map(Element::type).orElse("STRING");
    }
}
