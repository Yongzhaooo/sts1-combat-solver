package sts1solver;

import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Resolves an installed runtime without relying on the launcher's working directory. */
final class BackendRuntime {
    static Path dataDirectory(Properties properties) throws IOException {
        if (properties.getProperty("log") != null) {
            Path log = configuredPath(properties, "log");
            if (log.getParent() == null) throw new IOException("Configured log must name a file");
            return log.getParent();
        }
        String os = System.getProperty("os.name");
        Path home = Paths.get(System.getProperty("user.home"));
        if (os.startsWith("Mac"))
            return home.resolve("Library/Application Support/STS1CombatSolver");
        if (!os.startsWith("Windows")) {
            String state = System.getenv("XDG_STATE_HOME");
            Path base = state == null || state.isEmpty() ? home.resolve(".local/state") : Paths.get(state);
            if (!base.isAbsolute()) base = home.resolve(".local/state");
            return base.resolve("STS1CombatSolver");
        }
        String local = System.getenv("LOCALAPPDATA");
        return (local == null || local.isEmpty() ? home.resolve("AppData/Local")
                : Paths.get(local)).resolve("STS1CombatSolver");
    }

    static ProcessBuilder process(Properties properties) throws IOException {
        String mode = properties.getProperty("runtime", "wsl");
        if ("native".equals(mode)) {
            Path python = configuredFile(properties, "python"), backend = configuredFile(properties, "backend");
            if (!Files.isExecutable(python)) throw new IOException("Configured python is not executable: " + python);
            ProcessBuilder process = new ProcessBuilder(python.toString(), "-X", "utf8", "-B", "-u", backend.toString());
            process.directory(backend.getParent().toFile());
            process.environment().remove("PYTHONHOME");
            process.environment().remove("PYTHONPATH");
            process.environment().put("STS_SOLVER_DATA", dataDirectory(properties).toString());
            return process;
        }
        if ("wsl".equals(mode)) {
            if (!System.getProperty("os.name").startsWith("Windows"))
                throw new IOException("WSL runtime requires Windows; build a native runtime for macOS/Linux");
            return new ProcessBuilder("wsl.exe", "--exec", "env", "PYTHONUTF8=1", "PYTHONIOENCODING=utf-8",
                properties.getProperty("python"), "-u", properties.getProperty("backend"));
        }
        if (!"windows-bundled".equals(mode) && !"bundled".equals(mode))
            throw new IOException("Unknown solver runtime: " + mode);
        boolean mac = System.getProperty("os.name").startsWith("Mac") && "bundled".equals(mode);
        if (!mac && !System.getProperty("os.name").startsWith("Windows"))
            throw new IOException("This runtime requires Windows x64");
        Path directory = null;
        try {
            for (ModInfo mod : Loader.MODINFOS)
                if ("sts1solver".equals(mod.ID)) directory = Paths.get(mod.jarURL.toURI()).getParent();
        } catch (Exception failure) { throw new IOException("Cannot locate installed solver", failure); }
        if (directory == null) throw new IOException("Solver is missing from the loaded mod list");
        Path runtime = directory.resolve("sts1-solver-runtime");
        if (mac) {
            String arch = macArchitecture(System.getProperty("os.arch"));
            runtime = unpack(directory.resolve("sts1-solver-macos-" + arch + ".zip"), dataDirectory(properties));
        }
        Path python = runtime.resolve(mac ? "python/bin/python3" : "python/python.exe");
        Path backend = runtime.resolve("overlay/backend.py");
        if (!Files.isRegularFile(python) || !Files.isRegularFile(backend))
            throw new IOException("Bundled backend missing; reinstall the complete mod package");
        ProcessBuilder process = new ProcessBuilder(python.toString(), "-X", "utf8", "-B", "-u", backend.toString());
        process.directory(runtime.toFile());
        process.environment().remove("PYTHONHOME");
        process.environment().remove("PYTHONPATH");
        process.environment().put("STS_SOLVER_DATA", dataDirectory(properties).toString());
        return process;
    }

    static String macArchitecture(String arch) throws IOException {
        if ("aarch64".equals(arch) || "arm64".equals(arch)) return "arm64";
        // An Intel JVM under Rosetta uses the Intel bundle, independently of the physical CPU.
        if ("x86_64".equals(arch) || "amd64".equals(arch)) return "x86_64";
        throw new IOException("Unsupported macOS architecture: " + arch);
    }

    static Path unpack(Path archive, Path data) throws IOException {
        MessageDigest hash;
        try { hash = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        byte[] buffer = new byte[65536];
        try (InputStream input = Files.newInputStream(archive)) {
            for (int size; (size = input.read(buffer)) != -1;) hash.update(buffer, 0, size);
        }
        StringBuilder digest = new StringBuilder();
        for (byte b : hash.digest()) digest.append(String.format("%02x", b & 255));
        Path cache = data.resolve("runtimes");
        Path installed = cache.resolve(digest.toString());
        if (Files.isRegularFile(installed.resolve(".complete"))) return installed;
        Files.createDirectories(cache);
        Path staging = Files.createTempDirectory(cache, "unpack-").toAbsolutePath().normalize();
        try {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    Path target = staging.resolve(entry.getName()).normalize();
                    if (!target.startsWith(staging)) throw new IOException("Invalid runtime archive path");
                    if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target);
                }
            }
            Path python = staging.resolve("python/bin/python3");
            if (!Files.isRegularFile(python) || !Files.isRegularFile(staging.resolve("overlay/backend.py")))
                throw new IOException("Incomplete macOS runtime archive");
            // Workshop downloads made on Windows can lose Unix executable permissions.
            if (!python.toFile().setExecutable(true, true)) throw new IOException("Cannot make bundled Python executable");
            Files.createFile(staging.resolve(".complete"));
            try { Files.move(staging, installed); }
            catch (FileAlreadyExistsException e) {
                if (!Files.isRegularFile(installed.resolve(".complete"))) throw e;
            }
            return installed;
        } finally {
            if (Files.exists(staging)) {
                try (java.util.stream.Stream<Path> paths = Files.walk(staging)) {
                    for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                        Files.delete(path);
                }
            }
        }
    }

    private static Path configuredFile(Properties properties, String key) throws IOException {
        Path path = configuredPath(properties, key);
        if (!Files.isRegularFile(path)) throw new IOException("Native runtime " + key + " file missing: " + path);
        return path;
    }

    private static Path configuredPath(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isEmpty()) throw new IOException("Missing native runtime property: " + key);
        try {
            Path path = Paths.get(value);
            if (!path.isAbsolute()) throw new IOException("Runtime " + key + " must be an absolute path: " + value);
            return path;
        } catch (InvalidPathException failure) { throw new IOException("Invalid runtime path: " + key, failure); }
    }
}
