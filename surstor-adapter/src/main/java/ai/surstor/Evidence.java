package ai.surstor;

import java.util.ArrayList;
import java.util.List;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Evidence verification for SurStor artifacts and sessions.
 *
 * <p>Verifies the two-cell Merkle binding, session hash chains, and cost
 * recomputation against cited price schedules. Reports per-check pass/fail.</p>
 */
public final class Evidence {

    public static final AString K_VALID = Strings.create("valid");
    public static final AString K_CHECKS = Strings.create("checks");
    public static final AString K_BRANCH_REF_CHECK = Strings.create("branchRef");
    public static final AString K_CHAIN_CHECK = Strings.create("chain");
    public static final AString K_SCHEDULE_CHECK = Strings.create("schedule");
    public static final AString K_SIGNATURE_CHECK = Strings.create("signature");
    public static final AString K_MESSAGE = Strings.create("message");

    private Evidence() {}

    /**
     * Verifies a single artifact.
     *
     * @param engine venue engine
     * @param ctx request context
     * @param artifactId Cell 2 hash (full or prefix)
     * @param deep whether to walk the session chain
     * @return verification result map
     */
    public static AMap<AString, ACell> verify(Engine engine, RequestContext ctx, String artifactId, boolean deep) {
        AMap<AString, ACell> checks = Maps.empty();
        boolean valid = true;

        // Resolve artifact
        AMap<AString, ACell> cell2 = resolveCell2(engine, ctx, artifactId);
        if (cell2 == null) {
            return result(false, checks.assoc(K_MESSAGE, Strings.create("Artifact not found: " + artifactId)));
        }

        // Check 1: branch-ref binding
        Hash cell1Hash = Cells.branchRef(cell2);
        boolean branchOk = false;
        if (cell1Hash != null) {
            AMap<AString, ACell> cell1 = resolveCell1(engine, ctx, cell1Hash);
            branchOk = (cell1 != null) && Cells.verifyBranchRef(cell2, cell1);
        }
        checks = checks.assoc(K_BRANCH_REF_CHECK, CVMBool.create(branchOk));
        if (!branchOk) valid = false;

        // Check 2: session chain (deep verify)
        boolean chainOk = true;
        if (deep) {
            chainOk = verifySessionChain(engine, ctx, cell2);
        }
        checks = checks.assoc(K_CHAIN_CHECK, CVMBool.create(chainOk));
        if (!chainOk) valid = false;

        // Check 3: price schedule resolution and cost recomputation
        boolean scheduleOk = verifySchedule(engine, ctx, cell2);
        checks = checks.assoc(K_SCHEDULE_CHECK, CVMBool.create(scheduleOk));
        if (!scheduleOk) valid = false;

        // Check 4: node signature over the unsigned cell hash
        boolean signatureOk = verifySignature(cell2);
        checks = checks.assoc(K_SIGNATURE_CHECK, CVMBool.create(signatureOk));
        if (!signatureOk) valid = false;

        return result(valid, checks);
    }

    /**
     * Verifies the node signature embedded in a Cell 2. The signature covers
     * the hash of the unsigned form (cell minus signature/signer fields), so
     * it can be checked offline given only the cell — the signer account key
     * is embedded in the record.
     *
     * @param cell2 metadata cell
     * @return true if a valid signature is present
     */
    public static boolean verifySignature(AMap<AString, ACell> cell2) {
        ACell sigCell = cell2.get(Cells.K_SIGNATURE);
        ACell signerCell = cell2.get(Cells.K_SIGNER);
        if (!(sigCell instanceof AString) || !(signerCell instanceof AString)) return false;
        try {
            convex.core.data.AccountKey signer = convex.core.data.AccountKey.fromHex(signerCell.toString());
            convex.core.crypto.ASignature sig = convex.core.crypto.ASignature.fromHex(sigCell.toString());
            if (signer == null || sig == null) return false;
            return sig.verify(Cells.unsignedCell2(cell2).getHash(), signer);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verifies the session chain for a Cell 2.
     *
     * @param engine venue engine
     * @param ctx request context
     * @param cell2 metadata cell
     * @return true if chain is intact
     */
    public static boolean verifySessionChain(Engine engine, RequestContext ctx, AMap<AString, ACell> cell2) {
        Hash prev = Cells.prevEvent(cell2);
        if (prev == null) return true; // genesis event

        AMap<AString, ACell> prevCell2 = resolveCell2(engine, ctx, prev.toHexString());
        if (prevCell2 == null) return false;

        // Verify branch-ref and signature of previous event
        Hash prevCell1Hash = Cells.branchRef(prevCell2);
        if (prevCell1Hash == null) return false;
        AMap<AString, ACell> prevCell1 = resolveCell1(engine, ctx, prevCell1Hash);
        if (prevCell1 == null || !Cells.verifyBranchRef(prevCell2, prevCell1)) return false;
        if (!verifySignature(prevCell2)) return false;

        // Recurse
        return verifySessionChain(engine, ctx, prevCell2);
    }

    /**
     * Verifies that the cost in Cell 2 recomputes against its cited schedule.
     *
     * @param engine venue engine
     * @param ctx request context
     * @param cell2 metadata cell
     * @return true if schedule resolves and cost matches
     */
    public static boolean verifySchedule(Engine engine, RequestContext ctx, AMap<AString, ACell> cell2) {
        Hash scheduleId = Cells.priceScheduleId(cell2);
        String costUsd = Cells.costUsd(cell2);
        AMap<AString, ACell> tokenCount = Cells.tokenCount(cell2);

        // No claimed cost → nothing to recompute
        if (costUsd == null && cell2.get(Billing.KEY) == null) return true;
        if (scheduleId == null) return false;

        AMap<AString, ACell> schedule = resolveSchedule(engine, ctx, scheduleId);
        if (schedule == null || !schedule.getHash().equals(scheduleId)) return false;

        // If we have token count but no model version, we can't recompute
        return Billing.verify(cell2, schedule);
    }

    /**
     * Resolves a Cell 2 by artifact ID (full hash or prefix).
     *
     * @param engine venue engine
     * @param ctx request context
     * @param artifactId full hash or ss_ prefix
     * @return Cell 2 map, or null if not found
     */
    public static AMap<AString, ACell> resolveCell2(Engine engine, RequestContext ctx, String artifactId) {
        Hash hash = resolveHash(engine, ctx, artifactId);
        if (hash == null) return null;
        return resolveCell2ByHash(engine, ctx, hash);
    }

    /**
     * Resolves a Cell 2 by its hash.
     *
     * @param engine venue engine
     * @param ctx request context
     * @param hash Cell 2 hash
     * @return Cell 2 map, or null if not found
     */
    public static AMap<AString, ACell> resolveCell2ByHash(Engine engine, RequestContext ctx, Hash hash) {
        // Try user's asset store first
        AVector<?> record = engine.getAssetRecord(hash, ctx);
        if (record == null) {
            // Try venue asset store
            record = engine.getAssetRecord(hash, engine.getDIDString());
        }
        if (record == null) return null;

        ACell metaCell = record.get(covia.venue.AssetStore.POS_META);
        return RT.ensureMap(metaCell);
    }

    /**
     * Resolves a Cell 1 by its hash.
     *
     * @param engine venue engine
     * @param ctx request context
     * @param cell1Hash Cell 1 hash
     * @return Cell 1 map, or null if not found
     */
    public static AMap<AString, ACell> resolveCell1(Engine engine, RequestContext ctx, Hash cell1Hash) {
        AVector<?> record = engine.getAssetRecord(cell1Hash, ctx);
        if (record == null) {
            record = engine.getAssetRecord(cell1Hash, engine.getDIDString());
        }
        if (record == null) return null;

        ACell metaCell = record.get(covia.venue.AssetStore.POS_META);
        return RT.ensureMap(metaCell);
    }

    /**
     * Resolves a price schedule by its hash.
     *
     * @param engine venue engine
     * @param ctx request context
     * @param scheduleId schedule hash
     * @return schedule map, or null if not found
     */
    public static AMap<AString, ACell> resolveSchedule(Engine engine, RequestContext ctx, Hash scheduleId) {
        AVector<?> record = engine.getAssetRecord(scheduleId, ctx);
        if (record == null) {
            record = engine.getAssetRecord(scheduleId, engine.getDIDString());
        }
        if (record == null) return null;

        ACell metaCell = record.get(covia.venue.AssetStore.POS_META);
        return RT.ensureMap(metaCell);
    }

    /**
     * Resolves an artifact ID string to a full hash.
     *
     * @param engine venue engine
     * @param ctx request context
     * @param artifactId full hex hash or ss_ prefix
     * @return full hash, or null if not found
     */
    public static Hash resolveHash(Engine engine, RequestContext ctx, String artifactId) {
        if (artifactId == null) return null;
        String s = artifactId.startsWith("ss_") ? artifactId.substring(3) : artifactId;

        // Full hash
        if (s.length() == 64) {
            return Hash.parse(s);
        }

        // Prefix search in user's assets
        Hash found = findByPrefix(engine, ctx, s);
        if (found != null) return found;

        // Prefix search in venue assets
        return findByPrefix(engine, engine.getDIDString(), s);
    }

    private static Hash findByPrefix(Engine engine, RequestContext ctx, String prefix) {
        covia.venue.User user = engine.getVenueState().users().get(ctx.getUserDID());
        if (user == null) return null;
        return findByPrefix(user.assets().getAll(), prefix);
    }

    private static Hash findByPrefix(Engine engine, AString userDID, String prefix) {
        covia.venue.User user = engine.getVenueState().users().get(userDID);
        if (user == null) return null;
        return findByPrefix(user.assets().getAll(), prefix);
    }

    private static Hash findByPrefix(AMap<ABlob, AVector<?>> assets, String prefix) {
        if (assets == null) return null;
        for (long i = 0; i < assets.count(); i++) {
            var entry = assets.entryAt(i);
            if (entry == null) continue;
            ABlob key = entry.getKey();
            String hex = key.toHexString();
            if (hex.startsWith(prefix)) return Hash.wrap(key.getBytes());
        }
        return null;
    }

    private static String getString(AMap<AString, ACell> m, AString k) {
        ACell v = m.get(k);
        return (v instanceof AString) ? v.toString() : null;
    }

    private static AMap<AString, ACell> result(boolean valid, AMap<AString, ACell> checks) {
        return Maps.of(K_VALID, CVMBool.create(valid), K_CHECKS, checks,
            Strings.create("warning"),Strings.create(Billing.WARNING));
    }
}
