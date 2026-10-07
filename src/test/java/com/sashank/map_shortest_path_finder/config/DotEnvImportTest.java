package com.sashank.map_shortest_path_finder.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ".env" mechanism: application.properties imports a dotenv-style file, whose KEY=value lines feed the
 * ${GEMINI_API_KEY:...} placeholders. These tests point the same import at a temp file (the real one is resolved
 * relative to the working directory) so they never depend on — or leak — a developer's actual secrets.
 */
class DotEnvImportTest {

    @AfterEach
    void clearSystemProperty() { System.clearProperty("GEMINI_API_KEY"); }

    private ApplicationContextRunner runnerImporting(Path envFile) {
        return new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())   // loads the project's real application.properties
            .withPropertyValues("spring.config.import=optional:file:" + envFile + "[.properties]");
    }

    @Test
    void applicationProperties_declaresTheOptionalDotEnvImport() throws Exception {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(props.contains("spring.config.import=optional:file:.env[.properties]"),
            "application.properties must import the .env file (optional, so a missing file is not an error)");
    }

    @Test
    void valuesInTheDotEnvFile_reachTheSpringAiProperties(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
            # comment lines are fine
            INTENT_LLM_PROVIDER=google-genai
            GEMINI_API_KEY=key-from-dotenv
            INTENT_LLM_MODEL=gemini-test-model
            """);

        runnerImporting(env).run(ctx -> {
            var e = ctx.getEnvironment();
            assertEquals("google-genai", e.getProperty("spring.ai.model.chat"));
            assertEquals("key-from-dotenv", e.getProperty("spring.ai.google.genai.api-key"));
            assertEquals("gemini-test-model", e.getProperty("spring.ai.google.genai.chat.model"));
        });
    }

    @Test
    void googleApiKey_isAcceptedAsAnAlternativeNameForTheGeminiKey(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "GOOGLE_API_KEY=alt-name-key\n");

        runnerImporting(env).run(ctx ->
            assertEquals("alt-name-key", ctx.getEnvironment().getProperty("spring.ai.google.genai.api-key")));
    }

    @Test
    void aMissingDotEnvFile_isNotAnError_andLeavesTheDefaults(@TempDir Path dir) {
        runnerImporting(dir.resolve("does-not-exist.env")).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals("anthropic", ctx.getEnvironment().getProperty("spring.ai.model.chat"));
            assertEquals("", ctx.getEnvironment().getProperty("spring.ai.google.genai.api-key"));
        });
    }

    @Test
    void aRealEnvironmentValue_beatsTheDotEnvFile(@TempDir Path dir) throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "GEMINI_API_KEY=from-file\n");
        System.setProperty("GEMINI_API_KEY", "from-real-environment");   // same precedence tier as an OS variable

        runnerImporting(env).run(ctx ->
            assertEquals("from-real-environment", ctx.getEnvironment().getProperty("spring.ai.google.genai.api-key")));
    }

    @Test
    void theShippedTemplate_isSafeToCopy_andNeverSelectsGeminiWithAnEmptyKey() throws Exception {
        // Copying .env.example straight to .env must not crash startup (provider + empty key would).
        for (String file : new String[]{".env.example", ".env"}) {
            Path p = Path.of(file);
            if (!Files.exists(p)) continue;   // .env is local and may be absent (e.g. in CI)
            var lines = Files.readAllLines(p);
            boolean providerActive = lines.stream().anyMatch(l -> l.startsWith("INTENT_LLM_PROVIDER=google-genai"));
            boolean keyFilled = lines.stream().anyMatch(l -> l.matches("GEMINI_API_KEY=\\S+"));
            assertFalse(providerActive && !keyFilled,
                file + " selects Gemini but has no key: the app would refuse to start");
        }
        assertTrue(Files.exists(Path.of(".env.example")), ".env.example must be committed");
    }
}
