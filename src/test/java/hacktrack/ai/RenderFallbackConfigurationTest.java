package hacktrack.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the deployment switches behind the render fallback so a defaulted-off
 * environment variable cannot silently disable it again.
 */
class RenderFallbackConfigurationTest {

    @Test
    void renderFallbackIsEnabledByDefault() {
        assertTrue(new AiProperties().isRenderFallbackEnabled(),
                "headless-browser fallback must default to enabled");
    }

    @Test
    void applicationPropertiesDefaultTheFallbackToTrue() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties must be on the test classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("hacktrack.ai.render-fallback-enabled=${RENDER_FALLBACK_ENABLED:true}"),
                    "RENDER_FALLBACK_ENABLED must default to true");
            assertTrue(text.contains("hacktrack.ai.render-timeout-seconds=${RENDER_TIMEOUT_SECONDS:45}"));
            assertTrue(text.contains("hacktrack.ai.min-text-chars-for-render-fallback=${RENDER_MIN_TEXT_CHARS:400}"));
        }
    }
}
