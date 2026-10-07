package sts1solver;

import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

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
        if (!"windows-bundled".equals(mode)) throw new IOException("Unknown solver runtime: " + mode);
        if (!System.getProperty("os.name").startsWith("Windows"))
            throw new IOException("This runtime requires Windows x64");
        Path directory = null;
        try {
            for (ModInfo mod : Loader.MODINFOS)
                if ("sts1solver".equals(mod.ID)) directory = Paths.get(mod.jarURL.toURI()).getParent();
        } catch (Exception failure) { throw new IOException("Cannot locate installed solver", failure); }
        if (directory == null) throw new IOException("Solver is missing from the loaded mod list");
        Path runtime = directory.resolve("sts1-solver-runtime");
        Path python = runtime.resolve("python/python.exe"), backend = runtime.resolve("overlay/backend.py");
        if (!Files.isRegularFile(python) || !Files.isRegularFile(backend))
            throw new IOException("Bundled backend missing; reinstall the complete mod package");
        ProcessBuilder process = new ProcessBuilder(python.toString(), "-X", "utf8", "-B", "-u", backend.toString());
        process.directory(runtime.toFile());
        process.environment().put("STS_SOLVER_DATA", dataDirectory(properties).toString());
        return process;
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
