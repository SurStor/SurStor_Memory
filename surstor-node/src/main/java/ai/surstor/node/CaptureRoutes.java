package ai.surstor.node;

import ai.surstor.PrivacyFilter;
import convex.core.data.*;
import convex.core.util.JSON;
import covia.venue.Engine;
import covia.venue.server.AuthMiddleware;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** HTTP guard executes before Covia handlers create persistent jobs. */
final class CaptureRoutes {
    private static final String PREVIEW = "/api/v1/capture/preview";
    private static final String COMMIT = "/api/v1/capture/commit";
    private final String policyFile;
    private final Clock clock;
    private final Map<String, Pending> pending = new HashMap<>();
    private volatile Engine engine;
    private record Pending(ACell input, PrivacyFilter.Policy policy, AString owner, long expires) {}

    CaptureRoutes(String policyFile) { this(policyFile, Clock.systemUTC()); }
    CaptureRoutes(String policyFile, Clock clock) {
        this.policyFile = policyFile; this.clock = clock;
        PrivacyFilter.load(policyFile); // Invalid configured policy prevents startup.
    }
    void attach(Engine engine) { this.engine = engine; }

    void register(RoutesConfig routes) {
        routes.before(this::guard);
        routes.get("/capture", ctx -> {
            ctx.header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
            ctx.contentType("text/html; charset=utf-8").result(resource("capture.html"));
        });
        routes.get("/capture.js", ctx -> ctx.contentType("application/javascript").result(resource("capture.js")));
        routes.get("/capture.css", ctx -> ctx.contentType("text/css").result(resource("capture.css")));
        routes.post(PREVIEW, this::preview, covia.venue.server.VenueRouteFeature.COVIA_API);
        routes.post(COMMIT, this::commit, covia.venue.server.VenueRouteFeature.COVIA_API);
    }

    private void guard(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        ctx.header("Referrer-Policy", "no-referrer");
        ctx.header("X-Content-Type-Options", "nosniff");
        try {
            // Check Host as well as Origin: prevents browser DNS-rebinding to loopback.
            String host = ctx.header("Host");
            int port = ctx.req().getLocalPort();
            if (!Set.of("127.0.0.1:" + port, "localhost:" + port, "[::1]:" + port).contains(host)) throw new PrivacyFilter.Blocked();
            String origin = ctx.header("Origin");
            if (origin != null && !origin.equals("http://" + host)) throw new PrivacyFilter.Blocked();
            if ("cross-site".equals(ctx.header("Sec-Fetch-Site"))) throw new PrivacyFilter.Blocked();
            if (ctx.header("Content-Encoding") != null) throw new PrivacyFilter.Blocked();
            if (ctx.req().getContentLengthLong() > PrivacyFilter.MAX_CHARS) throw new PrivacyFilter.Blocked();
            // The preview is the ONLY path allowed to receive unfiltered content.
            // It runs in memory, without JobManager, logging or durable staging.
            byte[] body = ctx.bodyAsBytes();
            if (body.length > PrivacyFilter.MAX_CHARS) throw new PrivacyFilter.Blocked();
            if (body.length != 0) {
                String type = ctx.contentType();
                if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) throw new PrivacyFilter.Blocked();
                ACell input = PrivacyFilter.parse(new String(body, StandardCharsets.UTF_8));
                if (!(PREVIEW.equals(ctx.path()) && "POST".equals(ctx.method().toString()))) {
                    PrivacyFilter.requireClean(input, PrivacyFilter.load(policyFile));
                }
            }
        } catch (Exception e) { blocked(ctx); }
    }

    private synchronized void preview(Context ctx) {
        try {
            prune();
            if (pending.size() >= 64) throw new PrivacyFilter.Blocked();
            PrivacyFilter.Policy policy = PrivacyFilter.load(policyFile);
            ACell input = PrivacyFilter.parse(ctx.body());
            if (!(input instanceof AMap<?, ?> map) || map.get(Strings.create("content")) == null) throw new PrivacyFilter.Blocked();
            for (ACell key : map.keySet()) {
                if (!Set.of("content", "contentType", "sessionId", "agentId", "modelVersion", "usage", "billingContext").contains(key.toString())) throw new PrivacyFilter.Blocked();
            }
            PrivacyFilter.Result filtered = PrivacyFilter.filter(input, policy);
            PrivacyFilter.requireClean(filtered.value(), policy);
            AMap<?, ?> clean = (AMap<?, ?>) filtered.value();
            ACell type = clean.get(Strings.create("contentType"));
            ai.surstor.Cells.buildCell1(clean.get(Strings.create("content")), ai.surstor.Cells.canonicalContentType(type == null ? "text" : type.toString()));
            String token = UUID.randomUUID().toString();
            pending.put(token, new Pending(filtered.value(), policy, AuthMiddleware.getCallerDID(ctx), clock.millis() + 300_000));
            reply(ctx, Maps.of(Strings.create("token"), Strings.create(token),
                Strings.create("input"), filtered.value(), Strings.create("redactions"), convex.core.data.prim.CVMLong.create(filtered.redactions()),
                Strings.create("warning"), Strings.create("Automated detection can miss sensitive data. Review every field. Only this filtered version will be saved.")));
        } catch (Exception e) { blocked(ctx); }
    }

    private synchronized void commit(Context ctx) {
        try {
            prune();
            ACell request = PrivacyFilter.parse(ctx.body());
            if (!(request instanceof AMap<?, ?> map) || map.count() != 1 || !(map.get(Strings.create("token")) instanceof AString token)) throw new PrivacyFilter.Blocked();
            Pending approved = pending.get(token.toString());
            if (approved == null || !java.util.Objects.equals(approved.owner(), AuthMiddleware.getCallerDID(ctx))) throw new PrivacyFilter.Blocked();
            PrivacyFilter.Policy current = PrivacyFilter.load(policyFile);
            if (!current.equals(approved.policy()) || engine == null) throw new PrivacyFilter.Blocked();
            PrivacyFilter.requireClean(approved.input(), current);
            pending.remove(token.toString()); // Single attempt; failure must not replay a partial store.
            AMap<AString, ACell> meta = Maps.of(Strings.create("operation"), Maps.of(Strings.create("adapter"), Strings.create("surstor:store")));
            ACell result = engine.getAdapter("surstor").invokeFuture(AuthMiddleware.callerContext(ctx), meta, approved.input()).get(30, TimeUnit.SECONDS);
            engine.syncState();
            reply(ctx, result);
        } catch (Exception e) {
            // No exception text: downstream errors can include submitted values.
            ctx.status(409).result("Capture could not be confirmed. Review again; check existing records before retrying a save.");
        }
    }

    private void prune() { pending.values().removeIf(p -> p.expires() <= clock.millis()); }
    private static void reply(Context ctx, ACell value) { ctx.contentType("application/json").result(JSON.print(value).toString()); }
    private static void blocked(Context ctx) {
        ctx.status(422).result("Capture blocked. Check the privacy policy, request format, and filtered review. Nothing from this request was submitted to storage.");
        ctx.skipRemainingHandlers();
    }
    private static String resource(String name) throws java.io.IOException {
        try (var stream = CaptureRoutes.class.getResourceAsStream("/capture/" + name)) {
            if (stream == null) throw new java.io.IOException("Capture interface unavailable");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
