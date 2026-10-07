package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.exception.LlmUnavailableException;
import com.sashank.map_shortest_path_finder.exception.LlmUnavailableException.Kind;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real stack — Spring AI autoconfiguration, the project's real application.properties, the
 * vendor SDK and its HTTP client — against a local stand-in for the provider's API. No network,
 * no key. This is what proves that (a) the property names in application.properties actually take
 * effect, (b) real SDK exceptions are classified correctly, (c) failures are bounded in time, and
 * (d) what goes over the wire contains neither coordinates nor the user's contact details.
 */
class SpringAiProviderIntegrationTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> apiKeys = Collections.synchronizedList(new ArrayList<>());
    private volatile int status = 200;
    private volatile String responseBody;
    private volatile long delayMillis = 0;

    private static final String OK_INTENT =
        "{\"status\":\"OK\",\"destination\":\"railway station\",\"objective\":\"FASTEST\","
        + "\"constraints\":{\"avoidTolls\":true},\"preferences\":{\"preferWellLit\":true}}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeys.add(String.valueOf(ex.getRequestHeaders().getFirst("x-api-key")));
            try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) { }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            try {
                ex.sendResponseHeaders(status, out.length);
                ex.getResponseBody().write(out);
            } catch (IOException ignored) { /* client gave up (timeout test) */ }
            ex.close();
        });
        server.start();
        okWith(OK_INTENT);
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    private void okWith(String assistantText) {
        status = 200;
        responseBody = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-4-5-20251001\","
            + "\"content\":[{\"type\":\"text\",\"text\":" + quote(assistantText) + "}],\"stop_reason\":\"end_turn\","
            + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":10}}";
    }

    private void errorWith(int httpStatus, String type) {
        status = httpStatus;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"SECRET-PROVIDER-DETAIL\"}}";
    }

    private static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    private ApplicationContextRunner runner(String... extraProps) {
        List<String> props = new ArrayList<>(List.of(
            "spring.config.import=", // do not pick up the developer's real .env: these tests must not depend on it
            "spring.ai.anthropic.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
            "spring.ai.anthropic.api-key=test-key"));
        props.addAll(List.of(extraProps));
        return new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer()) // loads the project's real application.properties
            .withConfiguration(AutoConfigurations.of(
                AnthropicChatAutoConfiguration.class, OpenAiChatAutoConfiguration.class, GoogleGenAiChatAutoConfiguration.class,
                ChatClientAutoConfiguration.class,
                ToolCallingAutoConfiguration.class, SpringAiRetryAutoConfiguration.class))
            .withBean(IntentConfig.class)
            .withBean(SpringAiIntentParser.class)
            .withPropertyValues(props.toArray(String[]::new));
    }

    private void withParser(ApplicationContextRunner r, Consumer<SpringAiIntentParser> test) {
        r.run(ctx -> {
            assertNull(ctx.getStartupFailure(), () -> "context failed: " + ctx.getStartupFailure());
            test.accept(ctx.getBean(SpringAiIntentParser.class));
        });
    }

    @Test
    void happyPath_overTheRealSdk_producesIntent_andSendsNoCoordinatesOrContactDetails() {
        withParser(runner(), parser -> {
            RouteIntent i = parser.parse("fastest way to the railway station from 16.98912, 81.77871, no tolls, lit roads, mail a@b.com");

            assertEquals("railway station", i.destination());
            assertTrue(i.constraints().avoidTolls());
            assertTrue(i.preferences().preferWellLit());

            assertEquals(1, requests.get());
            assertEquals("test-key", apiKeys.get(0));
            String wire = bodies.get(0);
            assertTrue(wire.contains("railway station"));
            assertFalse(wire.contains("16.98912"), "coordinates reached the provider");
            assertFalse(wire.contains("81.77871"), "coordinates reached the provider");
            assertFalse(wire.contains("a@b.com"), "e-mail reached the provider");
            assertTrue(wire.contains("claude-haiku-4-5-20251001"), "model from application.properties not applied");
            assertTrue(wire.contains("avoidUnpaved"), "schema not sent");
        });
    }

    @Test
    void rateLimit_isClassified_andRetriesAreBounded() {
        errorWith(429, "rate_limit_error");
        long t0 = System.nanoTime();
        withParser(runner("spring.ai.anthropic.max-retries=0"), parser -> {
            var ex = assertThrows(LlmUnavailableException.class, () -> parser.parse("station"));
            assertEquals(Kind.RATE_LIMITED, ex.getKind());
            assertFalse(ex.getMessage().contains("SECRET-PROVIDER-DETAIL"));
        });
        assertTrue(requests.get() <= 2, "unbounded retries: " + requests.get());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 8_000, "took too long");
    }

    @Test
    void rejectedCredentials_areClassifiedAsConfiguration() {
        errorWith(401, "authentication_error");
        withParser(runner("spring.ai.anthropic.max-retries=0"), parser ->
            assertEquals(Kind.CONFIGURATION, assertThrows(LlmUnavailableException.class, () -> parser.parse("station")).getKind()));
    }

    @Test
    void providerServerError_isGenericUnavailable_withoutLeakingDetail() {
        errorWith(500, "api_error");
        withParser(runner("spring.ai.anthropic.max-retries=0"), parser -> {
            var ex = assertThrows(LlmUnavailableException.class, () -> parser.parse("station"));
            assertEquals(Kind.UNAVAILABLE, ex.getKind());
            assertFalse(ex.getMessage().contains("SECRET-PROVIDER-DETAIL"));
        });
    }

    @Test
    void slowProvider_timesOutAsConfigured_insteadOfHanging() {
        delayMillis = 6_000;
        long t0 = System.nanoTime();
        withParser(runner("spring.ai.anthropic.timeout=1s", "spring.ai.anthropic.max-retries=0"), parser ->
            assertEquals(Kind.TIMEOUT, assertThrows(LlmUnavailableException.class, () -> parser.parse("station")).getKind()));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_500, "timeout property not honoured");
    }

    @Test
    void anthropicSelectedWithoutAKey_startsUp_andFailsFastWithoutSendingAnythingToTheProvider() {
        withParser(runner("spring.ai.anthropic.api-key="), parser -> {
            var ex = assertThrows(LlmUnavailableException.class, () -> parser.parse("station near 16.98912, 81.77871"));
            assertEquals(Kind.NOT_CONFIGURED, ex.getKind());
            assertTrue(ex.getMessage().contains("ANTHROPIC_API_KEY"));
        });
        assertEquals(0, requests.get(), "request text must not be sent to a provider we have no credentials for");
    }

    @Test
    void providerNone_startsUp_andReportsNotConfigured() {
        withParser(runner("spring.ai.model.chat=none"), parser ->
            assertEquals(Kind.NOT_CONFIGURED, assertThrows(LlmUnavailableException.class, () -> parser.parse("station")).getKind()));
        assertEquals(0, requests.get());
    }

    @Test
    void openAiProvider_isSelectableByProperty() {
        runner("spring.ai.model.chat=openai", "spring.ai.openai.api-key=sk-test").run(ctx -> {
            assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
            assertEquals(1, ctx.getBeansOfType(org.springframework.ai.chat.model.ChatModel.class).size());
            assertTrue(ctx.getBean(org.springframework.ai.chat.model.ChatModel.class).getClass().getSimpleName().contains("OpenAi"));
        });
    }

    // ── Gemini ───────────────────────────────────────────────────────────────
    // The Spring AI Gemini integration has no base-url property, so these cover wiring and configuration only;
    // its error classification is covered by LlmErrorClassifierTest and its deadline by SpringAiIntentParserTest.

    @Test
    void gemini_isSelectableByProperty_andUsesTheConfiguredModelAndLimits() {
        runner("spring.ai.model.chat=google-genai", "spring.ai.google.genai.api-key=test-key").run(ctx -> {
            assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
            var models = ctx.getBeansOfType(org.springframework.ai.chat.model.ChatModel.class);
            assertEquals(1, models.size(), "exactly one provider must be active");
            var model = models.values().iterator().next();
            assertTrue(model.getClass().getSimpleName().contains("GoogleGenAi"), model.getClass().getName());
            assertEquals("gemini-2.5-flash", model.getDefaultOptions().getModel());   // from application.properties
            assertEquals(2048, model.getDefaultOptions().getMaxTokens());
            assertEquals(0.0, model.getDefaultOptions().getTemperature());
        });
    }

    @Test
    void gemini_withoutAKey_failsAtStartupWithSpringAisClearMessage_whichIsWhyItIsNotTheDefault() {
        runner("spring.ai.model.chat=google-genai", "spring.ai.google.genai.api-key=").run(ctx -> {
            assertNotNull(ctx.getStartupFailure());
            Throwable t = ctx.getStartupFailure();
            while (t.getCause() != null) t = t.getCause();
            assertTrue(t.getMessage().contains("api-key"), t.getMessage());
        });
    }

    @Test
    void theDefaultProvider_stillStartsWithoutAnyKey() {
        // nothing selected: application.properties' default (anthropic) must boot with no credentials at all
        runner("spring.ai.anthropic.api-key=").run(ctx -> assertNull(ctx.getStartupFailure()));
    }
}
