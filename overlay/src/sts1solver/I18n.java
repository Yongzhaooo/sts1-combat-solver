package sts1solver;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Presentation only: protocol IDs, snapshots and commands never pass through here. */
final class I18n {
    private static String language = "zh";
    private static final Map<String, String> english = new LinkedHashMap<>();
    private static final Map<String, String> backend = new LinkedHashMap<>();
    private static Pattern backendPattern;
    static {
        load("/localization/en.tsv", english);
        load("/localization/backend-en.tsv", backend);
        List<String> keys = new ArrayList<>(backend.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        StringJoiner pattern = new StringJoiner("|");
        for (String key : keys) pattern.add(Pattern.quote(key));
        backendPattern = Pattern.compile("(?<![\\p{IsHan}])(?:" + pattern + ")(?![\\p{IsHan}])");
    }
    private static void load(String resource, Map<String, String> target) {
        try (InputStream stream = I18n.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing " + resource);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int split = line.indexOf('|');
                    if (split <= 0) throw new IOException("Invalid translation: " + line);
                    String key = line.substring(0, split);
                    if (target.put(key, line.substring(split + 1)) != null)
                        throw new IOException("Duplicate translation: " + key);
                }
            }
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    static String language() { return language; }
    static void setLanguage(String value) { language = "en".equals(value) ? "en" : "zh"; }
    static String t(String value) { return "en".equals(language) ? english.getOrDefault(value, value) : value; }
    static String backend(String value) {
        if (!"en".equals(language)) return value;
        // ponytail: the legacy backend composes Chinese fragments; use one longest-first
        // pass. Replace this adapter with message keys if the backend protocol is revised.
        Matcher matcher = backendPattern.matcher(value);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(output, Matcher.quoteReplacement(backend.get(matcher.group())));
        matcher.appendTail(output);
        return output.toString();
    }
}
