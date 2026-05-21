package org.example.util;

import java.nio.file.Paths;

/**
 * Sanitizes filenames received from peers so a malicious sender cannot use
 * {@code ../etc/passwd}-style paths to write outside the downloads directory.
 */
public final class Filenames {

    private static final int MAX_LENGTH = 200;

    private Filenames() {}

    /**
     * Returns a safe filename derived from {@code raw}: stripped to its basename,
     * with only {@code [A-Za-z0-9._-]} kept, leading dots removed, length capped.
     *
     * @throws IllegalArgumentException if no valid characters remain
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Filename is empty");
        }
        // Take only the basename — drops any directory components a peer may have sent
        String base = Paths.get(raw).getFileName().toString();
        if (base.equals(".") || base.equals("..")) {
            throw new IllegalArgumentException("Invalid filename: " + raw);
        }
        // Replace anything outside a safe whitelist
        String cleaned = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        // Strip leading dots so we don't create hidden files
        cleaned = cleaned.replaceAll("^\\.+", "");
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("Filename has no valid characters: " + raw);
        }
        if (cleaned.length() > MAX_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LENGTH);
        }
        return cleaned;
    }
}
