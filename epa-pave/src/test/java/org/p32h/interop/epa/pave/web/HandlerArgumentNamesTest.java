package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Part;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.security.CodeSource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.PaveApplication;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Gradle build compiles with -parameters, an IDE such as STS compiles with its own compiler and
 * by default does not. A handler argument that takes its request name from its Java name then fails
 * on its first request, never at startup. This test holds every controller and controller advice of
 * the application to naming such arguments, so the application behaves the same whoever compiled it.
 */
class HandlerArgumentNamesTest {

    private static final String ROOT_PACKAGE = PaveApplication.class.getPackageName();

    private static final List<Class<? extends Annotation>> NAMED = List.of(PathVariable.class, RequestHeader.class, CookieValue.class,
            RequestPart.class, RequestAttribute.class, SessionAttribute.class, MatrixVariable.class, RequestParam.class);

    // Spring resolves these unannotated types from the request itself, not from a parameter of that name.
    private static final List<Class<?>> RESOLVED_WITHOUT_NAME = List.of(Locale.class, TimeZone.class, ZoneId.class);

    @Test
    void noHandlerArgumentOfTheApplicationReliesOnItsJavaName() {
        List<Class<?>> controllers = applicationControllers();
        assertThat(controllers)
                .as("No class annotated @Controller, @RestController or @ControllerAdvice was found under %s in the main classes, "
                        + "so this test would prove nothing", ROOT_PACKAGE)
                .isNotEmpty();

        List<String> offending = new ArrayList<>();
        for (Class<?> controller : controllers) {
            offending.addAll(argumentsRelyingOnJavaNames(controller));
        }
        assertThat(offending)
                .as("These handler arguments take their request name from their Java name, which only a build with the "
                        + "-parameters flag keeps. Name each one in its annotation, for example @PathVariable(\"runId\"):%n  %s",
                        String.join(System.lineSeparator() + "  ", offending))
                .isEmpty();
    }

    @Test
    void theCheckFindsEveryKindOfUnnamedArgumentAndPassesNamedOnes() {
        assertThat(argumentsRelyingOnJavaNames(Probe.class)).containsExactlyInAnyOrder(
                "Probe.unnamed argument 0 (String id): @PathVariable without a name",
                "Probe.unnamed argument 1 (String q): @RequestParam without a name",
                "Probe.unnamed argument 2 (String trace): @RequestHeader without a name",
                "Probe.unnamed argument 3 (String flavour): @CookieValue without a name",
                "Probe.unnamed argument 4 (MultipartFile file): @RequestPart without a name",
                "Probe.unnamed argument 5 (String seen): @RequestAttribute without a name",
                "Probe.unnamed argument 6 (String who): @SessionAttribute without a name",
                "Probe.unnamed argument 7 (String colour): @MatrixVariable without a name",
                "Probe.implicit argument 0 (String plain): a simple type without an annotation is read as a request parameter of its Java name",
                "Probe.implicit argument 1 (MultipartFile upload): a simple type without an annotation is read as a request parameter of its Java name");
    }

    /** Every controller and controller advice among the application's own classes, found by scanning. */
    private static List<Class<?>> applicationControllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(ControllerAdvice.class));
        CodeSource main = PaveApplication.class.getProtectionDomain().getCodeSource();
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(ROOT_PACKAGE)) {
            Class<?> type = ClassUtils.resolveClassName(Objects.requireNonNull(candidate.getBeanClassName()),
                    HandlerArgumentNamesTest.class.getClassLoader());
            // Test classes share the root package; their probe controllers are not part of the application.
            if (Objects.equals(type.getProtectionDomain().getCodeSource().getLocation(), main.getLocation())) {
                found.add(type);
            }
        }
        return found;
    }

    private static List<String> argumentsRelyingOnJavaNames(Class<?> type) {
        List<String> offending = new ArrayList<>();
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type, ReflectionUtils.USER_DECLARED_METHODS)) {
            boolean handler = MergedAnnotations.from(method).isPresent(RequestMapping.class);
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                String problem = problem(parameters[i], handler);
                if (problem != null) {
                    offending.add(type.getSimpleName() + "." + method.getName() + " argument " + i + " ("
                            + parameters[i].getType().getSimpleName() + " " + parameters[i].getName() + "): " + problem);
                }
            }
        }
        return offending;
    }

    private static String problem(Parameter parameter, boolean handler) {
        MergedAnnotations annotations = MergedAnnotations.from(parameter);
        for (Class<? extends Annotation> kind : NAMED) {
            MergedAnnotation<? extends Annotation> annotation = annotations.get(kind);
            if (!annotation.isPresent()) {
                continue;
            }
            // A Map or MultiValueMap argument takes every request parameter and has no name to look up.
            if (kind == RequestParam.class && Map.class.isAssignableFrom(parameter.getType())) {
                return null;
            }
            // name and value are aliases, so the merged view reports either one under both.
            return annotation.getString("name").isEmpty() ? "@" + kind.getSimpleName() + " without a name" : null;
        }
        if (handler && parameter.getAnnotations().length == 0 && readAsRequestParameter(parameter.getType())) {
            return "a simple type without an annotation is read as a request parameter of its Java name";
        }
        return null;
    }

    private static boolean readAsRequestParameter(Class<?> type) {
        if (RESOLVED_WITHOUT_NAME.stream().anyMatch(resolved -> resolved.isAssignableFrom(type))) {
            return false;
        }
        return BeanUtils.isSimpleProperty(type) || MultipartFile.class.isAssignableFrom(type) || Part.class.isAssignableFrom(type);
    }

    @RestController
    static class Probe {

        @GetMapping("/probe/{id}")
        public String unnamed(@PathVariable String id, @RequestParam(required = false) String q, @RequestHeader String trace,
                              @CookieValue String flavour, @RequestPart MultipartFile file, @RequestAttribute String seen,
                              @SessionAttribute String who, @MatrixVariable String colour) {
            return "probe";
        }

        @GetMapping("/probe/implicit")
        public String implicit(String plain, MultipartFile upload, Locale locale) {
            return "probe";
        }

        @GetMapping("/probe/{id}/named")
        public String named(@PathVariable("id") String id, @RequestParam(name = "q") String q, @RequestHeader("X-Trace") String trace,
                            @RequestParam Map<String, String> all, @RequestParam MultiValueMap<String, String> every) {
            return "probe";
        }
    }
}
