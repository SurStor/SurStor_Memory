package ai.surstor.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.surstor.Cells;
import ai.surstor.Billing;
import ai.surstor.Costs;
import ai.surstor.Evidence;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * Offline verifier for SurStor export bundles ({@code surstor-export/1}).
 *
 * <p>Recomputes every hash and signature from the bundle alone — no venue, no
 * network. Checks, per artifact: Cell 2 hash is its claimed ID; branch-ref
 * matches the bundled Cell 1; node signature is valid for the embedded signer.
 * For the session: the chain walks from the recorded head to genesis with no
 * gaps. Costs are recomputed against the bundled price schedules.</p>
 */
public final class OfflineVerifier {

    public record CheckResult(String name, boolean ok, String detail) {}

    public record BundleResult(boolean valid, List<CheckResult> checks) {
        public boolean ok() { return valid; }
    }

    private OfflineVerifier() {}

    /**
     * Verifies an export bundle.
     *
     * @param bundleJson the bundle file contents
     * @return verification result with per-check detail
     */
    @SuppressWarnings("unchecked")
    public static BundleResult verifyBundle(String bundleJson) {
        List<CheckResult> checks = new ArrayList<>();
        boolean valid = true;

        ACell parsed;
        try {
            parsed = JSON.parse(bundleJson);
        } catch (Exception e) {
            return new BundleResult(false, List.of(new CheckResult("parse", false, "not valid JSON: " + e.getMessage())));
        }
        if (!(parsed instanceof AMap)) {
            return new BundleResult(false, List.of(new CheckResult("parse", false, "bundle is not a JSON object")));
        }
        AMap<AString, ACell> bundle = (AMap<AString, ACell>) parsed;

        // Format marker
        ACell format = bundle.get(Strings.create("format"));
        boolean formatOk = format != null && "surstor-export/1".equals(format.toString());
        checks.add(new CheckResult("format", formatOk,
            formatOk ? "surstor-export/1" : "unexpected format: " + format));
        if (!formatOk) valid = false;

        // Index artifacts by cell2 hash
        ACell artifactsCell = bundle.get(Strings.create("artifacts"));
        AVector<ACell> artifacts = (artifactsCell instanceof AVector) ? (AVector<ACell>) artifactsCell : null;
        if (artifacts == null || artifacts.count() == 0) {
            checks.add(new CheckResult("artifacts", false, "bundle contains no artifacts"));
            return new BundleResult(false, checks);
        }

        Map<Hash, AMap<AString, ACell>> byId = new HashMap<>();
        int artifactNum = 0;
        for (ACell entry : artifacts) {
            artifactNum++;
            AMap<AString, ACell> pair = RT.ensureMap(entry);
            AMap<AString, ACell> cell1 = RT.ensureMap(pair.get(Strings.create("cell1")));
            AMap<AString, ACell> cell2 = RT.ensureMap(pair.get(Strings.create("cell2")));
            if (cell1 == null || cell2 == null) {
                checks.add(new CheckResult("artifact[" + artifactNum + "]", false, "missing cell1 or cell2"));
                valid = false;
                continue;
            }

            Hash id = cell2.getHash();
            byId.put(id, cell2);

            // Branch-ref binding
            boolean branchOk = Cells.verifyBranchRef(cell2, cell1);
            checks.add(new CheckResult("artifact[" + artifactNum + "] branchRef", branchOk,
                branchOk ? "cell1 hash matches branch-ref" : "MISMATCH: cell1 content does not match branch-ref"));
            if (!branchOk) valid = false;

            // Node signature over the unsigned cell
            boolean sigOk = Evidence.verifySignature(cell2);
            checks.add(new CheckResult("artifact[" + artifactNum + "] signature", sigOk,
                sigOk ? "valid signature by " + signerHex(cell2) : "MISSING or INVALID signature"));
            if (!sigOk) valid = false;
        }

        // Chain walk from the recorded session head — detects dropped or
        // rewritten events: every prev-event must resolve inside the bundle.
        AMap<AString, ACell> session = RT.ensureMap(bundle.get(Strings.create("session")));
        Hash head = (session != null) ? ai.surstor.Sessions.head(session) : null;
        if (head == null) {
            checks.add(new CheckResult("chain", false, "session record missing or has no head"));
            valid = false;
        } else {
            boolean chainOk = true;
            String chainDetail = "walks head → genesis, " + artifacts.count() + " events";
            Hash cursor = head;
            long steps = 0;
            while (cursor != null) {
                AMap<AString, ACell> cell2 = byId.get(cursor);
                if (cell2 == null) {
                    chainOk = false;
                    chainDetail = "GAP: event not in bundle: " + cursor.toHexString();
                    break;
                }
                if (++steps > artifacts.count()) {
                    chainOk = false;
                    chainDetail = "chain longer than artifact count — cycle suspected";
                    break;
                }
                cursor = Cells.prevEvent(cell2);
            }
            if (chainOk && steps != artifacts.count()) {
                chainOk = false;
                chainDetail = "bundle contains " + (artifacts.count() - steps) + " artifact(s) not reachable from the session head";
            }
            checks.add(new CheckResult("chain", chainOk, chainDetail));
            if (!chainOk) valid = false;
        }

        // Cost recomputation against bundled schedules
        AMap<AString, ACell> schedules = RT.ensureMap(bundle.get(Strings.create("schedules")));
        int costsChecked = 0;
        boolean costsOk = true;
        String costDetail = "no cost claims";
        for (Map.Entry<Hash, AMap<AString, ACell>> e : byId.entrySet()) {
            AMap<AString, ACell> cell2 = e.getValue();
            String costUsd = Cells.costUsd(cell2);
            if (costUsd == null && cell2.get(Billing.KEY) == null) continue;
            costsChecked++;
            Hash scheduleId = Cells.priceScheduleId(cell2);
            AMap<AString, ACell> schedule = (schedules != null && scheduleId != null)
                ? RT.ensureMap(schedules.get(Cells.hashString(scheduleId))) : null;
            if (schedule == null || !schedule.getHash().equals(scheduleId) || !Billing.verify(cell2,schedule)) {
                costsOk = false;
                costDetail = "MISMATCH at artifact " + e.getKey().toHexString()
                    + ": billing breakdown, total, or schedule hash mismatch";
                break;
            }
        }
        if (costsChecked > 0 && costsOk) costDetail = costsChecked + " billing record(s) replay correctly. " + Billing.WARNING;
        checks.add(new CheckResult("costs", costsOk, costDetail));
        if (!costsOk) valid = false;

        return new BundleResult(valid, checks);
    }

    private static String signerHex(AMap<AString, ACell> cell2) {
        ACell s = cell2.get(Cells.K_SIGNER);
        if (s == null) return "(none)";
        String hex = s.toString();
        return hex.length() > 16 ? hex.substring(0, 16) + "…" : hex;
    }
}
