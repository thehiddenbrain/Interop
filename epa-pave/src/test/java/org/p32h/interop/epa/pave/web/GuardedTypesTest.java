package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.p32h.interop.epa.pave.PaveApplication;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The production guardrail rests on two types nothing outside the gateway can construct. A web
 * framework that builds handler arguments from request parameters is the one thing that could
 * construct them from the outside, so these tests pin that it cannot, and that no controller ever
 * asks it to.
 */
class GuardedTypesTest {

    private static final Set<Class<?>> GUARDED = Set.of(CallIntent.class, ProdUnlock.class, PreparedCall.class);

    @RestController
    static class ProbeController {
        static final AtomicReference<Object> received = new AtomicReference<>();

        @GetMapping("/probe/unlock")
        public String unlock(ProdUnlock unlock) {
            received.set(unlock);
            return "reached";
        }

        @GetMapping("/probe/call")
        public String call(PreparedCall call) {
            received.set(call);
            return "reached";
        }
    }

    @Test
    void theBinderCannotConstructAnUnlockOrAPreparedCallFromRequestParameters() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController()).setControllerAdvice(new ApiExceptionHandler()).build();
        ProbeController.received.set(null);

        int unlockStatus = mvc.perform(get("/probe/unlock").param("environmentName", "Onyx Production").param("epoch", "7"))
                .andReturn().getResponse().getStatus();
        int callStatus = mvc.perform(get("/probe/call").param("operation", "PAS_SUBMIT").param("method", "POST")
                        .param("url.value", "https://prod.example.invalid/Claim/$submit"))
                .andReturn().getResponse().getStatus();

        // Both are interfaces with no visible implementation, so there is nothing for the binder to build.
        assertThat(ProbeController.received.get()).isNull();
        assertThat(unlockStatus).isGreaterThanOrEqualTo(400);
        assertThat(callStatus).isGreaterThanOrEqualTo(400);
    }

    @Test
    void theGuardedTypesHaveNoImplementationVisibleOutsideTheirPackage() {
        for (Class<?> type : List.of(ProdUnlock.class, PreparedCall.class)) {
            assertThat(type.isInterface()).as(type.getSimpleName()).isTrue();
            assertThat(type.isSealed()).as(type.getSimpleName()).isTrue();
            for (Class<?> permitted : type.getPermittedSubclasses()) {
                assertThat(Modifier.isPublic(permitted.getModifiers())).as(permitted.getSimpleName() + " is not public").isFalse();
                assertThat(Modifier.isFinal(permitted.getModifiers())).as(permitted.getSimpleName() + " is final").isTrue();
                assertThat(permitted.getPackageName()).isEqualTo(type.getPackageName());
                for (var constructor : permitted.getDeclaredConstructors()) {
                    assertThat(Modifier.isPublic(constructor.getModifiers())).as(permitted.getSimpleName() + " constructor").isFalse();
                }
            }
        }
    }

    @Test
    void noControllerTakesAGuardedTypeOrAnythingThatContainsOne() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> offenders = new ArrayList<>();
        int handlers = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents(PaveApplication.class.getPackageName())) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            if (controller.getEnclosingClass() == GuardedTypesTest.class) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    continue;
                }
                handlers++;
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (containsGuarded(parameter, new HashSet<>())) {
                        offenders.add(controller.getSimpleName() + "." + method.getName() + "(" + parameter.getSimpleName() + ")");
                    }
                }
            }
        }
        assertThat(handlers).as("the scan found the controllers").isPositive();
        assertThat(offenders).as("a controller builds an intent from validated inputs and reads the unlock from the session; "
                + "it never accepts either from the request").isEmpty();
    }

    /**
     * A prepared call proves a request was judged. That the request sent is the one that was judged
     * rests on every request leaving through the shared executor, which sends the url and method
     * of the call and nothing else. A client written later could accept a prepared call and still
     * send to a url it built itself, and the build would stay green. This walks the main sources
     * and fails when a file outside {@code client/support} performs an HTTP exchange of its own.
     */
    @Test
    void noCodeOutsideTheSharedClientPerformsAnHttpExchange() throws IOException {
        Path main = Path.of("src/main/java");
        assertThat(Files.isDirectory(main)).as("the test runs from the project directory").isTrue();
        // Assembled so this file does not itself contain what it looks for.
        List<String> marks = List.of(".retr" + "ieve(", ".exch" + "ange(", ".toEnt" + "ity(", "sendAs" + "ync(", "openConn" + "ection(",
                "java.net" + ".http", "HttpURL" + "Connection", "new Soc" + "ket(");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                String path = file.toString().replace('\\', '/');
                if (path.contains("/client/support/")) {
                    continue;
                }
                String source = Files.readString(file);
                for (String mark : marks) {
                    if (source.contains(mark)) {
                        offenders.add(path.substring(path.lastIndexOf('/') + 1) + " uses " + mark);
                    }
                }
            }
        }
        assertThat(scanned).as("the scan found the sources").isGreaterThan(50);
        assertThat(offenders).as("every outbound request goes through client/support/ExchangeExecutor, built with RestClients.withTimeouts")
                .isEmpty();
    }

    private static boolean containsGuarded(Class<?> type, Set<Class<?>> seen) {
        if (type == null || !seen.add(type)) {
            return false;
        }
        if (GUARDED.contains(type)) {
            return true;
        }
        if (type.isArray()) {
            return containsGuarded(type.getComponentType(), seen);
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (containsGuarded(component.getType(), seen)) {
                    return true;
                }
            }
        }
        return false;
    }
}
