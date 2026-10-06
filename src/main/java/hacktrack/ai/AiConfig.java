package hacktrack.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the AI configuration properties.
 *
 * The API key is supplied entirely through the environment, e.g.
 * {@code GEMINI_API_KEY=...} before starting the application. See
 * application.properties for the property mapping.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {
}
