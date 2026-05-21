package org.example.servers;

import org.mindrot.jbcrypt.BCrypt;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * User registration and authentication.
 *
 * <p>Passwords are hashed with BCrypt (cost factor {@value #BCRYPT_COST}) and stored on
 * disk in the format {@code username:bcryptHash} — one user per line. The BCrypt hash
 * embeds its own random salt, so no separate salt storage is needed.
 *
 * <p>This is intentionally simple file-based storage. A production deployment would use
 * a real database with row-level locking.
 */
public final class AuthManager {

    /** OWASP-recommended minimum BCrypt cost as of 2023; ~250ms per hash on modern hardware. */
    private static final int BCRYPT_COST = 12;

    private static Path filePath = Path.of("users.txt");
    private static final Map<String, String> userDatabase = new HashMap<>();

    static {
        loadUserData();
    }

    private AuthManager() {}

    public static synchronized void setUserFilePath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path cannot be null");
        }
        filePath = path;
        loadUserData();
    }

    private static synchronized void loadUserData() {
        userDatabase.clear();
        if (!Files.exists(filePath)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int sep = line.indexOf(':');
                if (sep > 0) {
                    userDatabase.put(line.substring(0, sep), line.substring(sep + 1));
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load users: " + e.getMessage());
        }
    }

    public static synchronized boolean register(String username, String password) {
        if (username == null || username.isBlank() || username.contains(":")
                || password == null || password.isEmpty()) {
            return false;
        }
        if (userDatabase.containsKey(username)) {
            return false;
        }
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_COST));
        userDatabase.put(username, hash);
        saveUser(username, hash);
        return true;
    }

    public static synchronized boolean login(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        String hash = userDatabase.get(username);
        if (hash == null) {
            return false;
        }
        try {
            return BCrypt.checkpw(password, hash);
        } catch (Exception e) {
            return false;
        }
    }

    private static synchronized void saveUser(String username, String hash) {
        try {
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(username + ":" + hash);
                w.newLine();
            }
        } catch (IOException e) {
            System.err.println("Failed to save user: " + e.getMessage());
        }
    }
}
