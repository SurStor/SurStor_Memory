package ai.surstor.node;

import ai.surstor.*;
import convex.core.data.*;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.venue.RequestContext;
import covia.venue.server.VenueServer;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CaptureRoutesTest {
    @TempDir Path dir;
    private static final String SECRET = "sk-proj-private0123456789abcdefghijk";
    private static final class MutableClock extends Clock {
        Instant now = Instant.now();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    private HttpResponse<String> post(VenueServer server, String path, String body, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) request.headers(headers);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String token(HttpResponse<String> preview) {
        assertEquals(200, preview.statusCode(), preview.body());
        return RT.getIn(JSON.parse(preview.body()), Strings.create("token")).toString();
    }
    private String commitBody(String token) { return "{\"token\":\"" + token + "\"}"; }

    @Test void previewCommitIngressAndPolicyFailure() throws Exception {
        Path policy = dir.resolve("privacy.json");
        Files.writeString(policy, "{\"redactTerms\":[\"Client Orion\"],\"excludeTerms\":[\"NO-CAPTURE\"]}");
        MutableClock clock = new MutableClock();
        CaptureRoutes routes = new CaptureRoutes(policy.toString(), clock);
        var adapterConfig = Maps.of(Strings.create("search-enabled"), Strings.create("false"), Strings.create("privacy-policy-file"), Strings.create(policy.toString()));
        AMap<AString, ACell> config = Maps.of(Strings.create("port"), CVMLong.ZERO,
            Strings.create("bindAddress"), Strings.create("127.0.0.1"),
            Strings.create("store"), Strings.create(dir.resolve("capture.etch").toString()),
            Strings.create("auth"), Maps.of(Strings.create("public"), Maps.of(Strings.create("caps"), Strings.create("unrestricted"))),
            Strings.create("adapters"), Maps.of(Strings.create("surstor"), adapterConfig));
        VenueServer server = VenueServer.launch(config, List.of(routes::register));
        try {
            var engine = server.getEngine();
            var adapter = new SurStorAdapter(); engine.registerAdapter(adapter); routes.attach(engine);
            String raw = "{\"content\":{\"prompt\":\"Client Orion " + SECRET + "\",\"response\":\"ordinary answer\"},\"contentType\":\"conversation\"}";
            var preview = post(server, "/api/v1/capture/preview", raw);
            String token = token(preview);
            assertFalse(preview.body().contains(SECRET)); assertFalse(preview.body().contains("Client Orion"));
            var saved = post(server, "/api/v1/capture/commit", commitBody(token));
            assertEquals(200, saved.statusCode(), saved.body());
            ACell id = RT.getIn(JSON.parse(saved.body()), Strings.create("id"));
            RequestContext user = RequestContext.of(Strings.create(engine.getDIDString() + ":public"));
            AMap<AString, ACell> meta = Maps.of(Strings.create("operation"), Maps.of(Strings.create("adapter"), Strings.create("surstor:retrieve")));
            ACell record = adapter.invokeFuture(user, meta, Maps.of(Strings.create("id"), id)).get();
            assertFalse(record.toString().contains(SECRET)); assertTrue(record.toString().contains("REDACTED"));
            assertTrue(record.toString().contains("passed-storage-filter"));
            assertEquals(409, post(server, "/api/v1/capture/commit", commitBody(token)).statusCode());

            // Both REST and MCP are stopped before their operation/job handlers.
            for (String path : List.of("/api/v1/invoke", "/mcp")) {
                var blocked = post(server, path, "{\"operation\":\"surstor/store\",\"input\":" + raw + "}");
                assertEquals(422, blocked.statusCode()); assertFalse(blocked.body().contains(SECRET));
            }
            assertEquals(422, post(server, "/api/v1/capture/preview", raw, "Origin", "https://evil.example").statusCode());
            assertEquals(422, post(server, "/api/v1/capture/preview", "{\"content\":\"NO-CAPTURE\"}").statusCode());
            assertEquals(422, post(server, "/api/v1/capture/preview", "{broken").statusCode());

            String expired = token(post(server, "/api/v1/capture/preview", raw));
            clock.now = clock.now.plusSeconds(301);
            assertEquals(409, post(server, "/api/v1/capture/commit", commitBody(expired)).statusCode());
            String changed = token(post(server, "/api/v1/capture/preview", raw));
            Files.writeString(policy, "{\"redactTerms\":[\"different\"]}");
            assertEquals(409, post(server, "/api/v1/capture/commit", commitBody(changed)).statusCode());
            Files.writeString(policy, "broken policy");
            assertEquals(422, post(server, "/api/v1/capture/preview", raw).statusCode());
            assertEquals(422, post(server, "/api/v1/invoke", "{\"input\":\"ordinary\"}").statusCode());
            assertThrows(Exception.class, () -> adapter.invokeFuture(user,
                Maps.of(Strings.create("operation"), Maps.of(Strings.create("adapter"), Strings.create("surstor:store"))),
                Maps.of(Strings.create("content"), Strings.create("ordinary"))).get());
        } finally { server.close(); System.gc(); }
        // No raw preview values should reach the on-disk venue store.
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1).contains(SECRET), file.toString());
            }
        }
    }
}
