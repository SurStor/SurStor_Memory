package ai.surstor;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import convex.core.data.*;
import convex.core.data.prim.CVMLong;

class BillingTest {
    private static AMap<AString, ACell> json(String s) { return Pricing.parse(s); }
    private static AMap<AString, ACell> ctx(String time, String tier, String mode) {
        return Billing.context(Maps.of(Billing.key("usage-time"),CVMLong.create(Instant.parse(time).toEpochMilli()),
            Billing.key("service-tier"),Billing.key(tier),Billing.key("input-mode"),Billing.key(mode)),0);
    }
    private static AMap<AString, ACell> calc(String model,String usage) {
        return Billing.estimate(model,json(usage),Pricing.loadSeedSchedule(),ctx("2026-09-10T12:00:00Z","standard","exclusive"));
    }
    @Test void catalogIsValidAndEveryTariffCanBePriced() {
        var seed=Pricing.loadSeedSchedule();
        assertTrue(Pricing.isValid(seed));
        int models=0;
        for (ACell k:seed.keySet()) {
            if(k.toString().startsWith("_")) continue;
            models++;
            var entry=Billing.map(seed.get(k));
            for(ACell item:(AVector<?>)entry.get(Billing.key("tariffs"))) {
                var tariff=Billing.map(item);
                long input=tariff.get(Billing.key("min-input-exclusive"))==null ? 1000 : Long.parseLong(tariff.get(Billing.key("min-input-exclusive")).toString())+1;
                String from=Billing.str(tariff,"from","2026-09-10T00:00:00Z");
                var time=Instant.parse(from).atZone(java.time.ZoneOffset.UTC);
                if(tariff.get(Billing.key("time-band"))!=null) time=time.withHour(Billing.str(tariff,"time-band","").equals("peak")?2:12);
                // The future Pro transition is at 04:00; choose the second peak window.
                if(time.toInstant().isBefore(Instant.parse(from))) time=time.withHour(7);
                var billing=Billing.estimate(k.toString(),Costs.tokenCount(input,100,null),seed,
                    ctx(time.toInstant().toString(),Billing.str(tariff,"service-tier","standard"),"exclusive"));
                assertEquals("estimated",Billing.str(billing,"status",""),k+" "+tariff+" "+billing);
                assertEquals(tariff.get(Billing.key("id")),billing.get(Billing.key("tariff-id")));
            }
        }
        assertEquals(31,models);
        assertEquals(seed.getHash(),Pricing.parse(Pricing.toJson(seed).toString()).getHash());
    }
    @Test void openaiTotalInputDoesNotDoubleChargeReadsOrWrites() {
        var billing=Billing.estimate("openai/gpt-6-astra",json("{\"input\":10000,\"output\":1000,\"cached-input\":4000,\"cache-write\":2000}"),
            Pricing.loadSeedSchedule(),ctx("2026-09-10T12:00:00Z","standard","total"));
        // 4000 ordinary*10 + 4000 cached*1 + 2000 writes*12.5 + 1000 output*50, per million.
        assertEquals("0.119000",Billing.total(billing));
        assertEquals("4000",Billing.map(billing.get(Billing.key("quantities"))).get(Costs.K_INPUT).toString());
    }
    @Test void claudeWritesAndSearchAreSeparateCharges() {
        assertEquals("0.026000",Billing.total(calc("anthropic/claude-sonnet-5",
            "{\"input\":1000,\"output\":100,\"cached-input\":5000,\"cache-write-5m\":4000,\"cache-write-1h\":500,\"web-search-requests\":1}")));
    }
    @Test void kimiUsesPublishedCachePrice() {
        assertEquals("0.019000",Billing.total(calc("moonshot/kimi-k2.7-code","{\"input\":0,\"output\":0,\"cached-input\":100000}")));
    }
    @Test void fractionalStorageAndSearch() {
        assertEquals("0.518500",Billing.total(calc("google/gemini-3.8-flash",
            "{\"input\":1000,\"output\":1000,\"cache-storage-token-hours\":\"1000000.5\",\"web-search-requests\":1}")));
    }
    @Test void tierAndContextBoundaries() {
        var s=Pricing.loadSeedSchedule();
        for(long n:new long[]{272000,272001}) {
            var b=Billing.estimate("openai/gpt-6-astra",Costs.tokenCount(n,0,null),s,ctx("2026-09-10T12:00:00Z","batch","exclusive"));
            assertEquals(n==272000?"1.360000":"2.720010",Billing.total(b));
        }
        assertEquals("0.399998",Billing.total(calc("xai/grok-4.6","{\"input\":199999,\"output\":0}")));
        assertEquals("0.800000",Billing.total(calc("xai/grok-4.6","{\"input\":200000,\"output\":0}")));
        assertEquals("0.400000",Billing.total(calc("google/gemini-3.1-pro-preview","{\"input\":200000,\"output\":0}")));
        assertEquals("0.800004",Billing.total(calc("google/gemini-3.1-pro-preview","{\"input\":200001,\"output\":0}")));
    }
    @Test void deepseekWeekdaysAndTimeBoundariesAndRouting() {
        String[] times={"2026-09-10T00:59:59Z","2026-09-10T01:00:00Z","2026-09-10T04:00:00Z","2026-09-10T06:00:00Z","2026-09-10T10:00:00Z","2026-09-12T02:00:00Z","2026-09-14T03:59:59Z","2026-09-14T04:00:00Z"};
        String[] expected={"0.660000","1.320000","0.660000","1.320000","0.660000","0.660000","1.320000","0.150000"};
        for(int i=0;i<times.length;i++) assertEquals(expected[i],Billing.total(Billing.estimate("deepseek/deepseek-v4-pro",
            Costs.tokenCount(1000000,0,null),Pricing.loadSeedSchedule(),ctx(times[i],"standard","exclusive"))),times[i]);
        var b=Billing.estimate("deepseek/deepseek-flash",Costs.tokenCount(1,0,null),Pricing.loadSeedSchedule(),Billing.context(null,Instant.parse(times[0]).toEpochMilli()));
        assertNull(Billing.total(b));
        assertTrue(Billing.str(b,"reason","").contains("usage-time"));
    }
    @Test void announcedChangesSelectNewRatesAndUnits() {
        var seed=Pricing.loadSeedSchedule();
        assertEquals("1.500000",Billing.total(Billing.estimate("google/gemini-3.8-flash",Costs.tokenCount(1000000,0,null),seed,ctx("2027-01-01T00:00:00Z","standard","exclusive"))));
        var usage=json("{\"input\":0,\"output\":0,\"x-search-requests\":1}");
        assertEquals("0.005000",Billing.total(Billing.estimate("xai/grok-4.6",usage,seed,ctx("2026-09-21T18:59:59Z","standard","exclusive"))));
        assertNull(Billing.total(Billing.estimate("xai/grok-4.6",usage,seed,ctx("2026-09-21T19:00:00Z","standard","exclusive"))));
        assertEquals("0.020000",Billing.total(Billing.estimate("xai/grok-4.6",json("{\"input\":0,\"output\":0,\"x-posts-fetched\":2,\"x-profiles-fetched\":1}"),seed,ctx("2026-09-21T19:00:00Z","standard","exclusive"))));
    }
    @Test void missingOrUnsupportedUsageNeverBecomesFree() {
        assertNull(Billing.total(calc("unknown/model","{\"input\":10,\"output\":10}")));
        assertNull(Billing.total(calc("openai/gpt-5","{\"input\":10}")));
        assertNull(Billing.total(calc("openai/gpt-5","{\"input\":10,\"output\":1,\"unknown-fee\":1}")));
        assertNull(Billing.total(Billing.estimate("openai/gpt-5",Costs.tokenCount(1,1,null),Pricing.loadSeedSchedule(),ctx("2026-09-10T00:00:00Z","unlisted","exclusive"))));
        assertThrows(IllegalArgumentException.class,()->calc("openai/gpt-5","{\"input\":-1,\"output\":0}"));
        assertThrows(IllegalArgumentException.class,()->calc("openai/gpt-5","{\"input\":\"1.5\",\"output\":0}"));
        assertThrows(IllegalArgumentException.class,()->Billing.estimate("openai/gpt-5",Costs.tokenCount(1,0,2L),Pricing.loadSeedSchedule(),ctx("2026-09-10T00:00:00Z","standard","total")));
    }
    @Test void fullBillingRoundTripAndTampering() {
        var seed=Pricing.loadSeedSchedule(); var usage=Costs.tokenCount(1000,500,null);
        var b=Billing.estimate("anthropic/claude-sonnet-5",usage,seed,ctx("2026-09-10T12:00:00Z","standard","exclusive"));
        var cell=Cells.buildCell2(Cells.buildCell1(Billing.key("test"),"text"),"s","a","anthropic/claude-sonnet-5",0,usage,Billing.total(b),seed.getHash(),null).assoc(Billing.KEY,b);
        assertTrue(Billing.verify(cell,seed));
        var parsed=Cells.parseCell2(Cells.toJson(cell).toString());
        assertEquals(cell.getHash(),parsed.getHash());
        assertTrue(Billing.verify(parsed,seed));
        assertFalse(Billing.verify(cell.assoc(Billing.KEY,b.assoc(Billing.key("version"),CVMLong.create(3))),seed));
        assertFalse(Billing.verify(cell.assoc(Billing.KEY,b.assoc(Billing.key("tariff-id"),Billing.key("fake"))),seed));
        assertFalse(Billing.verify(cell.assoc(Cells.K_COST_USD,Billing.key("0.000000")),seed));
        assertFalse(Billing.verify(cell.dissoc(Billing.KEY),seed));
    }
    @Test void legacyReplaySurvivesNewCatalog() {
        var old=Pricing.loadResource(Pricing.LEGACY_SEED_RESOURCE); var usage=Costs.tokenCount(1000,500,null);
        var cell=Cells.buildCell2(Cells.buildCell1(Billing.key("old"),"text"),"s","a","anthropic/claude-sonnet-5",0,usage,"0.010500",old.getHash(),null);
        assertTrue(Billing.verify(cell,old));
        assertFalse(Billing.verify(cell,Pricing.loadSeedSchedule()));
    }
    @Test void rejectsMalformedSchedulesAndAmbiguity() {
        assertFalse(Pricing.isValid(json("{\"_comment\":\"empty\"}")));
        assertFalse(Pricing.isValid(Pricing.loadSeedSchedule().assoc(Billing.key("_schema-version"),CVMLong.create(3))));
        var seed=Pricing.loadSeedSchedule(); var model=Billing.map(seed.get(Billing.key("openai/gpt-5")));
        var tariffs=(AVector<ACell>)model.get(Billing.key("tariffs"));
        var t=Billing.map(tariffs.get(0));
        var malformed=seed.assoc(Billing.key("openai/gpt-5"),model.assoc(Billing.key("tariffs"),Vectors.of(t.assoc(Billing.key("invented-condition"),Billing.key("x")))));
        assertFalse(Pricing.isValid(malformed));
        var duplicate=seed.assoc(Billing.key("openai/gpt-5"),model.assoc(Billing.key("tariffs"),tariffs.conj(t.assoc(Billing.key("id"),Billing.key("other")))));
        assertNull(Billing.total(Billing.estimate("openai/gpt-5",Costs.tokenCount(1,1,null),duplicate,ctx("2026-09-10T00:00:00Z","standard","exclusive"))));
    }
    @Test void roundsOnlyOnceAfterSummingExactLines() {
        var b=calc("openai/gpt-5-mini","{\"input\":1,\"output\":0,\"cached-input\":10}");
        assertEquals("0.000001",Billing.total(b)); // .25 micro + .25 micro, round once.
    }
}
