package ai.surstor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import convex.core.data.*;
import convex.core.data.prim.CVMLong;

/** Versioned, deterministic estimation. No network, clocks, or floating point in replay. */
public final class Billing {
    public static final AString KEY = Strings.create("billing");
    public static final String WARNING = "Estimated API cost, not a provider invoice. Rates are a dated snapshot "
        + "transcribed from published documentation (or a user-installed schedule); usage is caller-supplied. "
        + "Only supplied, supported charges are calculated. Taxes, credits, subscriptions, negotiated discounts "
        + "and unreported usage are excluded. Prices and model aliases can change. Verification proves "
        + "record integrity and arithmetic, not provider endorsement or invoice accuracy. "
        + "Unpriced records are excluded from totals; the session budget is not a provider spending cap.";
    private static final Set<String> CONTEXT_KEYS = Set.of("usage-time", "time-source", "service-tier", "input-mode");
    private static final Set<String> INPUT_PARTS = Set.of("cached-input", "cache-write", "cache-write-5m", "cache-write-1h");
    private Billing() {}

    public static AString key(String s) { return Strings.create(s); }
    public static String str(AMap<AString, ACell> m, String k, String fallback) {
        ACell v = m.get(key(k));
        return v == null ? fallback : v.toString();
    }
    @SuppressWarnings("unchecked")
    public static AMap<AString, ACell> map(ACell v) {
        return v instanceof AMap ? (AMap<AString, ACell>) v : null;
    }
    public static boolean isV2(AMap<AString, ACell> schedule) {
        return schedule != null && CVMLong.create(2).equals(schedule.get(key("_schema-version")));
    }

    /** Resolve defaults once at capture. The resulting context is part of the signed cell. */
    public static AMap<AString, ACell> context(AMap<AString, ACell> input, long captureTime) {
        AMap<AString, ACell> c = input == null ? Maps.empty() : input;
        for (ACell k : c.keySet()) if (!CONTEXT_KEYS.contains(k.toString()))
            throw new IllegalArgumentException("Unknown billing context field: " + k);
        boolean supplied = c.get(key("usage-time")) != null;
        if (!supplied) c = c.assoc(key("usage-time"), CVMLong.create(captureTime));
        c = c.assoc(key("time-source"), key(supplied ? "caller" : "capture-time-fallback"));
        if (c.get(key("service-tier")) == null) c = c.assoc(key("service-tier"), key("standard"));
        if (c.get(key("input-mode")) == null) c = c.assoc(key("input-mode"), key("exclusive"));
        validateContext(c);
        return c;
    }

    private static void validateContext(AMap<AString, ACell> c) {
        if (c == null) throw new IllegalArgumentException("Billing context required");
        for (ACell k : c.keySet()) if (!CONTEXT_KEYS.contains(k.toString()))
            throw new IllegalArgumentException("Unknown billing context field: " + k);
        integer(c.get(key("usage-time")), "usage-time");
        if (!(c.get(key("service-tier")) instanceof AString)) throw new IllegalArgumentException("service-tier required");
        if (!Set.of("exclusive", "total").contains(str(c,"input-mode","")))
            throw new IllegalArgumentException("input-mode must be exclusive or total");
        if (!Set.of("caller", "capture-time-fallback").contains(str(c,"time-source","")))
            throw new IllegalArgumentException("Invalid time-source");
    }

    public static AMap<AString, ACell> estimate(String model, AMap<AString, ACell> usage,
            AMap<AString, ACell> schedule, AMap<AString, ACell> context) {
        validateContext(context);
        AMap<AString, ACell> result = Maps.of(key("version"), CVMLong.create(2),
            key("status"), key("unpriced"), key("basis"), key("published-list-price-estimate"),
            key("currency"), key("USD"), key("warning"), key(WARNING), key("context"), context);
        if (model == null || usage == null) return reason(result, "Model and usage are required for an estimate");
        if (!isV2(schedule)) return reason(result, "Unsupported schedule version");
        AMap<AString, ACell> normalized = Maps.empty();
        for (ACell k : usage.keySet()) {
            if (!(k instanceof AString)) throw new IllegalArgumentException("Usage keys must be strings");
            BigDecimal n = decimal(usage.get(k), "usage." + k);
            normalized = normalized.assoc((AString) k, key(n.toPlainString()));
        }
        result = result.assoc(key("quantities"), normalized);
        if (!usage.containsKey(Costs.K_INPUT) || !usage.containsKey(Costs.K_OUTPUT))
            return reason(result, "Both input and output counts are required (explicit zero is allowed)");
        BigDecimal input = decimal(usage.get(Costs.K_INPUT), "input");
        BigDecimal parts = BigDecimal.ZERO;
        for (String part : INPUT_PARTS) {
            BigDecimal n = quantity(normalized, part);
            if (n.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException(part + " must be an integer");
            parts = parts.add(n);
        }
        if (input.stripTrailingZeros().scale() > 0 || quantity(normalized,"output").stripTrailingZeros().scale() > 0)
            throw new IllegalArgumentException("Token counts must be integers");
        BigDecimal prompt = input.add(parts);
        if (str(context,"input-mode","").equals("total")) {
            prompt = input;
            input = input.subtract(parts);
            if (input.signum() < 0) throw new IllegalArgumentException("Cached/read/write tokens exceed total input");
            normalized = normalized.assoc(Costs.K_INPUT, key(input.toPlainString()));
        }
        result = result.assoc(key("quantities"), normalized).assoc(key("prompt-tokens"), key(prompt.toPlainString()));
        AMap<AString, ACell> entry = map(schedule.get(key(model)));
        if (entry == null) return reason(result, "Model is absent from the cited schedule: " + model);
        result = result.assoc(key("source-url"), entry.get(key("source-url")))
            .assoc(key("checked-at"), schedule.get(key("_checked-at")));
        AVector<?> tariffs = (AVector<?>) entry.get(key("tariffs"));
        AMap<AString, ACell> selected = null;
        long time = integer(context.get(key("usage-time")), "usage-time");
        for (ACell item : tariffs) {
            AMap<AString, ACell> t = map(item);
            if (!str(t,"service-tier","standard").equals(str(context,"service-tier","standard"))) continue;
            if (t.get(key("min-input-exclusive")) != null && prompt.compareTo(decimal(t.get(key("min-input-exclusive")),"min")) <= 0) continue;
            if (t.get(key("max-input")) != null && prompt.compareTo(decimal(t.get(key("max-input")),"max")) > 0) continue;
            if (t.get(key("from")) != null && time < Instant.parse(str(t,"from","")).toEpochMilli()) continue;
            if (t.get(key("until")) != null && time >= Instant.parse(str(t,"until","")).toEpochMilli()) continue;
            String band = str(t,"time-band","");
            if (!band.isEmpty()) {
                if (!str(context,"time-source","").equals("caller"))
                    return reason(result, "Time-dependent rates require the actual caller-supplied usage-time");
                boolean peak = peak(entry, time);
                if (peak != band.equals("peak")) continue;
            }
            if (selected != null) return reason(result, "Ambiguous schedule: multiple tariffs match");
            selected = t;
        }
        if (selected == null) return reason(result, "No tariff matches the service tier, usage time and prompt size");
        result = result.assoc(key("tariff-id"), selected.get(key("id")));
        AMap<AString, ACell> rates = map(selected.get(key("rates")));
        AMap<AString, ACell> lines = Maps.empty();
        BigDecimal total = BigDecimal.ZERO;
        for (ACell k : normalized.keySet()) {
            BigDecimal n = decimal(normalized.get(k),"quantity");
            AMap<AString, ACell> rate = map(rates.get(k));
            if (rate == null) {
                if (n.signum() != 0) return reason(result,"No rate for supplied charge: " + k);
                continue;
            }
            String unit = str(rate,"unit","");
            if (!unit.equals("token-hour") && n.stripTrailingZeros().scale() > 0)
                throw new IllegalArgumentException("Whole quantities required for " + k);
            BigDecimal amount = n.multiply(decimal(rate.get(key("usd")),"rate"))
                .divide(decimal(rate.get(key("per")),"per"));
            total = total.add(amount);
            lines = lines.assoc((AString) k, Maps.of(key("quantity"),key(n.toPlainString()),
                key("unit"),key(unit),key("per"),rate.get(key("per")), key("rate-usd"),rate.get(key("usd")),
                key("amount-usd"),key(amount.toPlainString())));
        }
        return result.assoc(key("status"),key("estimated")).assoc(key("line-items"),lines)
            .assoc(key("total-usd"),key(total.setScale(6,RoundingMode.HALF_UP).toPlainString()));
    }

    private static boolean peak(AMap<AString, ACell> entry, long millis) {
        AMap<AString, ACell> rule = map(entry.get(key("peak-utc")));
        var utc = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC);
        AVector<?> days = (AVector<?>) rule.get(key("weekdays"));
        boolean weekday = false;
        for (ACell d : days) if (integer(d,"weekday") == utc.getDayOfWeek().getValue()) weekday = true;
        if (!weekday) return false;
        int minute = utc.getHour()*60+utc.getMinute();
        for (ACell window : (AVector<?>) rule.get(key("minute-ranges"))) {
            AVector<?> bounds = (AVector<?>) window;
            if (minute >= integer(bounds.get(0),"start") && minute < integer(bounds.get(1),"end")) return true;
        }
        return false;
    }
    private static AMap<AString, ACell> reason(AMap<AString, ACell> m, String reason) {
        return m.assoc(key("reason"),key(reason));
    }
    public static String total(AMap<AString, ACell> billing) {
        return billing == null ? null : str(billing,"total-usd",null);
    }
    private static BigDecimal quantity(AMap<AString, ACell> usage, String key) {
        ACell v = usage.get(key(key));
        return v == null ? BigDecimal.ZERO : decimal(v,key);
    }
    static BigDecimal decimal(ACell v, String field) {
        if (!(v instanceof AString || v instanceof CVMLong)) throw new IllegalArgumentException(field + " must be an integer or decimal string");
        String s = v.toString();
        if (s.length() > 40 || !s.matches("[0-9]+(?:\\.[0-9]{1,12})?"))
            throw new IllegalArgumentException("Invalid nonnegative decimal: " + field);
        return new BigDecimal(s);
    }
    private static long integer(ACell v, String field) {
        if (!(v instanceof CVMLong n) || n.longValue() < 0) throw new IllegalArgumentException(field + " must be a nonnegative integer");
        return n.longValue();
    }

    /** Both verifiers use this entry point; never reinterpret legacy arithmetic as v2. */
    public static boolean verify(AMap<AString, ACell> cell, AMap<AString, ACell> schedule) {
        try {
            AMap<AString, ACell> billing = map(cell.get(KEY));
            if (cell.get(KEY) != null && billing == null) return false;
            String cost = Cells.costUsd(cell);
            String model = str(cell,"model-version",null);
            if (billing == null) {
                if (isV2(schedule)) return false;
                String computed = Costs.compute(model,Cells.tokenCount(cell),schedule);
                return computed != null && computed.equals(cost);
            }
            if (!CVMLong.create(2).equals(billing.get(key("version")))) return false;
            if (!validSchedule(schedule)) return false;
            AMap<AString, ACell> replay = estimate(model,Cells.tokenCount(cell),schedule,map(billing.get(key("context"))));
            return replay.equals(billing) && java.util.Objects.equals(total(replay),cost);
        } catch (RuntimeException e) { return false; }
    }

    /** Validate executable fields on installation; unsupported conditions must never be ignored. */
    public static boolean validSchedule(AMap<AString, ACell> schedule) {
        try {
            if (!isV2(schedule) || !"USD".equals(str(schedule,"_currency",""))) return false;
            Instant.parse(str(schedule,"_checked-at",""));
            int models = 0;
            for (ACell k : schedule.keySet()) {
                if (!(k instanceof AString)) return false;
                if (k.toString().startsWith("_")) continue;
                models++;
                AMap<AString, ACell> entry = map(schedule.get(k));
                for (ACell field : entry.keySet()) if (!Set.of("source-url","notes","tariffs","peak-utc").contains(field.toString())) return false;
                if (!str(entry,"source-url","").startsWith("https://")) return false;
                if (!(entry.get(key("tariffs")) instanceof AVector<?> tariffs) || tariffs.count() == 0) return false;
                Set<String> ids = new HashSet<>();
                for (ACell t : tariffs) {
                    AMap<AString, ACell> tariff = map(t);
                    for (ACell f : tariff.keySet()) if (!Set.of("id","service-tier","min-input-exclusive","max-input","from","until","time-band","rates").contains(f.toString())) return false;
                    if (str(tariff,"id","").isBlank() || !ids.add(str(tariff,"id",""))) return false;
                    if (!(tariff.get(key("service-tier")) instanceof AString)) return false;
                    for (String f : Set.of("min-input-exclusive","max-input")) if (tariff.containsKey(key(f))) decimal(tariff.get(key(f)),f).longValueExact();
                    if (tariff.containsKey(key("min-input-exclusive")) && tariff.containsKey(key("max-input"))
                            && decimal(tariff.get(key("min-input-exclusive")),"min").compareTo(decimal(tariff.get(key("max-input")),"max")) >= 0) return false;
                    for (String f : Set.of("from","until")) if (tariff.containsKey(key(f))) Instant.parse(str(tariff,f,""));
                    if (tariff.containsKey(key("from")) && tariff.containsKey(key("until")) && !Instant.parse(str(tariff,"from","")).isBefore(Instant.parse(str(tariff,"until","")))) return false;
                    if (tariff.containsKey(key("time-band"))) {
                        if (!Set.of("peak","off-peak").contains(str(tariff,"time-band",""))) return false;
                        AMap<AString, ACell> rule = map(entry.get(key("peak-utc")));
                        if (rule == null || rule.count()!=2) return false;
                        for (ACell d : (AVector<?>)rule.get(key("weekdays"))) if (integer(d,"day")<1 || integer(d,"day")>7) return false;
                        for (ACell w : (AVector<?>)rule.get(key("minute-ranges"))) {
                            AVector<?> bounds = (AVector<?>) w;
                            if (bounds.count()!=2 || integer(bounds.get(0),"start")>=integer(bounds.get(1),"end") || integer(bounds.get(1),"end")>1440) return false;
                        }
                    }
                    AMap<AString, ACell> rates = map(tariff.get(key("rates")));
                    if (rates == null || !rates.containsKey(Costs.K_INPUT) || !rates.containsKey(Costs.K_OUTPUT)) return false;
                    for (ACell metric : rates.keySet()) {
                        if (!(metric instanceof AString) || metric.toString().isBlank()) return false;
                        AMap<AString, ACell> rate = map(rates.get(metric));
                        if (rate == null || rate.count()!=3) return false;
                        String unit = str(rate,"unit","");
                        if (!Set.of("token","token-hour","request").contains(unit)) return false;
                        if ((INPUT_PARTS.contains(metric.toString()) || Set.of("input","output").contains(metric.toString())) && !unit.equals("token")) return false;
                        decimal(rate.get(key("usd")),"usd");
                        BigDecimal per = decimal(rate.get(key("per")),"per");
                        if (!Set.of("1","1000","1000000").contains(per.stripTrailingZeros().toPlainString())) return false;
                    }
                }
            }
            return models > 0;
        } catch (RuntimeException e) { return false; }
    }
}
