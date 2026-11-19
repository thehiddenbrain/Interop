package org.p32h.interop.epa.pave.testsupport;

import org.p32h.interop.epa.pave.config.JacksonConfig;
import org.p32h.interop.epa.pave.web.ApiExceptionHandler;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the MockMvc every controller test uses.
 *
 * <p>A standalone MockMvc does not use the application's JSON mapper. Built by hand it would read
 * request bodies with a default mapper, which drops an unknown property without a word, and a
 * controller test would never see that the running application refuses one. This builds the
 * mapper with the same call the application uses, and registers the one exception handler, so a
 * controller test sees the statuses and the error shape a browser will.
 */
public final class TestMvc {

    private TestMvc() {
    }

    /** The mapper the application reads request bodies with. */
    public static JsonMapper mapper() {
        return JacksonConfig.strict(JsonMapper.builder()).build();
    }

    public static MockMvc standalone(Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new ApiExceptionHandler())
                // In the order the application registers them: text and bytes are written as they
                // are, and only then does JSON take whatever is left.
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new StringHttpMessageConverter(),
                        new ResourceHttpMessageConverter(), new JacksonJsonHttpMessageConverter(mapper()))
                .build();
    }
}
