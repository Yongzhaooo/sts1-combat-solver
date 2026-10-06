package sts1solver;

import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Resolves an installed runtime without relying on the launcher's working directory. */
final class BackendRuntime {
    static Path dataDirectory(Properties properties) {
        if (!"windows-bundled".equals(properties.getProperty("runtime")))
            return Paths.get(properties.getProperty("log")).getParent();
        String local = System.getenv("LOCALAPPDATA");
        return (local == null || local.isEmpty() ? Paths.get(System.getProperty("user.home"), "AppData", "Local")
                : Paths.get(local)).resolve("STS1CombatSolver");
    }

    static ProcessBuilder process(Properties properties) throws IOException {
        if (!"windows-bundled".equals(properties.getProperty("runtime")))
            return new ProcessBuilder("wsl.exe", "--exec", "env", "PYTHONUTF8=1", "PYTHONIOENCODING=utf-8",
                properties.getProperty("python"), "-u", properties.getProperty("backend"));
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
}
