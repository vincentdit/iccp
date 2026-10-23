package tz.go.tirdo.iccp.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the modular-monolith rules of Doc 05 §3 (ADR-002) on the source tree:
 * <ol>
 *   <li>a module uses another module only through its {@code api} package;</li>
 *   <li>module dependencies follow the release order platform ← dir ← sec ← ntf ← com, fin (no cycles);</li>
 *   <li>the platform layer depends on no business module;</li>
 *   <li>SQL touches only the module's own schema plus the shared kernel ({@code dir}) and the platform
 *       schemas ({@code core}, {@code wf}, {@code integ}).</li>
 * </ol>
 * The {@code demo} package is development tooling and is exempt.
 */
class ModuleBoundaryTest {

    private static final Path ROOT = Path.of("src/main/java/tz/go/tirdo/iccp");
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "dir", Set.of(),
            "sec", Set.of("dir"),
            "ntf", Set.of("dir", "sec"),
            "com", Set.of("dir", "sec", "ntf"),
            "fin", Set.of("dir", "sec", "ntf"));
    private static final Set<String> SHARED_SCHEMAS = Set.of("dir", "core", "wf", "integ");
    private static final Set<String> ALL_SCHEMAS = Set.of("core", "dir", "sec", "wf", "integ", "ntf", "com", "fin", "mtg", "tsk", "msg", "doc",
            "prj", "req", "trn", "ana", "fdb");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?tz\\.go\\.tirdo\\.iccp\\.([a-z]+)\\.([a-zA-Z0-9_.]+);", Pattern.MULTILINE);
    private static final Pattern TABLE_REF = Pattern.compile("(?i)\\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+([a-z]+)\\.[a-z_]+");

    @Test
    void modulesRespectBoundaries() {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String module = ROOT.relativize(file).getName(0).toString();
            if (module.endsWith(".java") || "demo".equals(module)) {
                continue;
            }
            String src = read(file);
            Matcher m = IMPORT.matcher(src);
            while (m.find()) {
                String target = m.group(1), rest = m.group(2);
                if (target.equals(module) || "platform".equals(target)) {
                    continue;
                }
                if ("platform".equals(module)) {
                    violations.add(file + ": platform must not depend on module " + target);
                } else if (!rest.startsWith("api.")) {
                    violations.add(file + ": uses " + target + "." + rest + " (only " + target + ".api is published)");
                } else if (!ALLOWED.getOrDefault(module, Set.of()).contains(target)) {
                    violations.add(file + ": " + module + " may not depend on " + target + " (dependency order)");
                }
            }
            if (!"platform".equals(module)) {
                Matcher t = TABLE_REF.matcher(src);
                while (t.find()) {
                    String schema = t.group(1).toLowerCase();
                    if (ALL_SCHEMAS.contains(schema) && !schema.equals(module) && !SHARED_SCHEMAS.contains(schema)) {
                        violations.add(file + ": SQL reaches into schema " + schema);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Module boundary violations:\n" + String.join("\n", violations));
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> s = Files.walk(ROOT)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
