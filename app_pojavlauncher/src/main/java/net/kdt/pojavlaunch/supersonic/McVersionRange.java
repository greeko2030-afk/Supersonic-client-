package net.kdt.pojavlaunch.supersonic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches a Minecraft version against the version ranges that mods write in their metadata:
 * <ul>
 *   <li>semver ranges of Fabric and Quilt: {@code >=1.20}, {@code >=1.20 <1.21}, {@code ~1.20.1},
 *       {@code ^1.20}, {@code 1.20.x}, {@code 1.20.1 || 1.20.4}, {@code *}</li>
 *   <li>Maven ranges of Forge and NeoForge: {@code [1.20.1]}, {@code [1.20,1.21)}, {@code (,1.21)},
 *       {@code [1.20.1,)}</li>
 * </ul>
 * Only release versions such as 1.20.1 are compared by number. A snapshot or any other version id
 * only matches a range that names it exactly (or {@code *}), so a mod is never installed by a guess.
 */
final class McVersionRange {
    private McVersionRange() {}

    private static final Pattern RELEASE = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?$");
    private static final Pattern TOKEN = Pattern.compile("^(>=|<=|>|<|=|~|\\^)?(.*)$");
    private static final Pattern MAVEN_SET = Pattern.compile("([\\[(])([^\\[\\]()]*)([\\])])");

    /** @return {major, minor, patch} of a release such as 1.20.1, or null for a snapshot or other id */
    static int[] parseRelease(String version) {
        if (version == null) return null;
        Matcher m = RELEASE.matcher(version.trim());
        if (!m.matches()) return null;
        int[] result = new int[3];
        for (int i = 0; i < 3; i++) {
            String part = m.group(i + 1);
            result[i] = part == null ? 0 : Integer.parseInt(part);
        }
        return result;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ Fabric and Quilt

    /** @return true when the Minecraft version is inside the semver range */
    static boolean matchesSemver(String range, String mcVersion) {
        if (range == null || mcVersion == null) return false;
        String text = range.trim();
        if (text.isEmpty() || text.equals("*") || text.equalsIgnoreCase("x")) return true;
        int[] mc = parseRelease(mcVersion);
        for (String orPart : text.split("\\|\\|")) {
            if (allTermsMatch(orPart.trim(), mcVersion.trim(), mc)) return true;
        }
        return false;
    }

    private static boolean allTermsMatch(String part, String mcVersion, int[] mc) {
        if (part.isEmpty()) return false;
        // ">= 1.20" is the same as ">=1.20"
        String[] tokens = part.replaceAll("([<>=~^]+)\\s+", "$1").split("\\s+");
        for (String token : tokens) {
            if (!termMatches(token, mcVersion, mc)) return false;
        }
        return true;
    }

    private static boolean isWildcard(String part) {
        return part.equals("*") || part.equalsIgnoreCase("x");
    }

    private static boolean termMatches(String token, String mcVersion, int[] mc) {
        Matcher m = TOKEN.matcher(token);
        if (!m.matches()) return false;
        String op = m.group(1) == null ? "" : m.group(1);
        String version = m.group(2);

        if (mc == null) {
            // a snapshot: only a range that names it, or any version
            return isWildcard(version) || (op.isEmpty() || op.equals("=")) && version.equals(mcVersion);
        }

        // "1.21-" and "1.20.2-alpha.1" write a pre-release, releases are compared by their number
        int cut = indexOfAny(version, '-', '+');
        if (cut >= 0) version = version.substring(0, cut);
        if (version.isEmpty() || isWildcard(version)) return true;

        String[] parts = version.split("\\.");
        int[] base = new int[3];
        int specified = 0;
        for (int i = 0; i < parts.length && i < 3; i++) {
            if (isWildcard(parts[i])) break;
            if (!parts[i].matches("\\d+")) return false;
            base[i] = Integer.parseInt(parts[i]);
            specified++;
        }
        if (specified == 0) return true;

        switch (op) {
            case ">=":
                return compare(mc, base) >= 0;
            case ">":
                return specified == 3 ? compare(mc, base) > 0 : compare(mc, upper(base, specified)) >= 0;
            case "<":
                return compare(mc, base) < 0;
            case "<=":
                return specified == 3 ? compare(mc, base) <= 0 : compare(mc, upper(base, specified)) < 0;
            case "~":
                return compare(mc, base) >= 0 && compare(mc, tildeUpper(base, specified)) < 0;
            case "^":
                return compare(mc, base) >= 0 && compare(mc, caretUpper(base, specified)) < 0;
            default: // "" or "="
                if (specified == 3) return compare(mc, base) == 0;
                return compare(mc, base) >= 0 && compare(mc, upper(base, specified)) < 0;
        }
    }

    /** The first version after everything that starts with the specified numbers: 1.2 -> 1.3.0 */
    private static int[] upper(int[] base, int specified) {
        if (specified == 1) return new int[]{base[0] + 1, 0, 0};
        if (specified == 2) return new int[]{base[0], base[1] + 1, 0};
        return new int[]{base[0], base[1], base[2] + 1};
    }

    private static int[] tildeUpper(int[] base, int specified) {
        return specified == 1 ? new int[]{base[0] + 1, 0, 0} : new int[]{base[0], base[1] + 1, 0};
    }

    private static int[] caretUpper(int[] base, int specified) {
        if (base[0] > 0) return new int[]{base[0] + 1, 0, 0};
        if (specified == 1) return new int[]{1, 0, 0};
        if (base[1] > 0) return new int[]{0, base[1] + 1, 0};
        if (specified == 2) return new int[]{0, 1, 0};
        return new int[]{0, 0, base[2] + 1};
    }

    private static int indexOfAny(String text, char a, char b) {
        int first = text.indexOf(a);
        int second = text.indexOf(b);
        if (first < 0) return second;
        if (second < 0) return first;
        return Math.min(first, second);
    }

    // ------------------------------------------------------------------ Forge and NeoForge

    /**
     * @return true when the Minecraft version is inside the Maven range. A version without brackets
     * counts as exactly that version, which is stricter than Maven but never installs a wrong build.
     */
    static boolean matchesMaven(String range, String mcVersion) {
        if (range == null || mcVersion == null) return false;
        String text = range.trim();
        if (text.isEmpty()) return false;
        int[] mc = parseRelease(mcVersion);

        if (!text.startsWith("[") && !text.startsWith("(")) {
            int[] exact = parseRelease(text);
            if (mc == null || exact == null) return text.equals(mcVersion.trim());
            return compare(mc, exact) == 0;
        }
        if (mc == null) {
            return text.equals("[" + mcVersion.trim() + "]");
        }

        Matcher sets = MAVEN_SET.matcher(text);
        while (sets.find()) {
            boolean lowInclusive = sets.group(1).equals("[");
            boolean highInclusive = sets.group(3).equals("]");
            String body = sets.group(2).trim();

            if (!body.contains(",")) { // [1.20.1]
                int[] exact = parseRelease(body);
                if (exact != null && compare(mc, exact) == 0) return true;
                continue;
            }
            String[] ends = body.split(",", -1);
            String low = ends[0].trim();
            String high = ends[1].trim();
            boolean inside = true;
            if (!low.isEmpty()) {
                int[] bound = parseRelease(low);
                if (bound == null) inside = false;
                else inside = lowInclusive ? compare(mc, bound) >= 0 : compare(mc, bound) > 0;
            }
            if (inside && !high.isEmpty()) {
                int[] bound = parseRelease(high);
                if (bound == null) inside = false;
                else inside = highInclusive ? compare(mc, bound) <= 0 : compare(mc, bound) < 0;
            }
            if (inside) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ old Forge (mcmod.info)

    /** The mcversion of an old Forge mod names exactly one Minecraft version. */
    static boolean matchesExact(String declared, String mcVersion) {
        return declared != null && mcVersion != null && declared.trim().equals(mcVersion.trim());
    }
}
