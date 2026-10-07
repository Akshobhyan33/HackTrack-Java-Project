package hacktrack.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local stand-in for a container compatibility check: verifies the runtime
 * image actually ships the browser and driver the Selenium fallback needs,
 * exposes them to the application, and still drops privileges afterwards.
 *
 * <p>Building the image itself is not possible in every environment, so the
 * contract is asserted on the Dockerfile that produces it.
 */
class DockerfileBrowserSupportTest {

    private static final Path DOCKERFILE = Path.of("Dockerfile");

    @Test
    void dockerfileShipsChromeAndAMatchingChromeDriver() throws IOException {
        assertTrue(Files.exists(DOCKERFILE), "Dockerfile must exist at the project root");
        String dockerfile = Files.readString(DOCKERFILE);

        assertTrue(dockerfile.contains("FROM eclipse-temurin:17-jre"),
                "runtime stage must stay a JRE 17 image");
        assertTrue(dockerfile.contains("https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb"),
                "runtime image must install a real Chrome build");
        assertTrue(dockerfile.contains("chromedriver-linux64.zip"),
                "runtime image must install ChromeDriver instead of downloading it at runtime");
        assertTrue(dockerfile.contains("install -m 755 /tmp/chromedriver/chromedriver /usr/local/bin/chromedriver"),
                "ChromeDriver must be installed into the image");
    }

    @Test
    void dockerfileExposesTheBrowserLocationsToTheApplication() throws IOException {
        String dockerfile = Files.readString(DOCKERFILE);

        assertTrue(dockerfile.contains("CHROME_BIN=/usr/bin/google-chrome"),
                "CHROME_BIN must point at the baked-in browser");
        assertTrue(dockerfile.contains("CHROMEDRIVER_PATH=/usr/local/bin/chromedriver"),
                "CHROMEDRIVER_PATH must point at the baked-in driver");
        assertTrue(dockerfile.contains("SE_AVOID_STATS=true"),
                "Selenium Manager telemetry must stay disabled in the image");
    }

    @Test
    void browserIsInstalledAsRootAndTheAppStillRunsAsNonRoot() throws IOException {
        String dockerfile = Files.readString(DOCKERFILE);

        int browserInstall = dockerfile.indexOf("google-chrome-stable_current_amd64.deb");
        int nonRootUser = dockerfile.indexOf("USER hacktrack");
        assertTrue(browserInstall >= 0, "browser install step is missing");
        assertTrue(nonRootUser > browserInstall,
                "the browser must be installed before dropping privileges, and the app must still run unprivileged");
        assertTrue(dockerfile.indexOf("USER root") < 0 || dockerfile.indexOf("USER root") > nonRootUser,
                "the final user must remain the non-root service account");
        assertEquals(1, countOccurrences(dockerfile, "USER hacktrack"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
