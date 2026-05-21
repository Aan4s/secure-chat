package org.example.servers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AuthManagerTest {

    @Test
    void registerAndLoginRoundTrip(@TempDir Path tmp) {
        AuthManager.setUserFilePath(tmp.resolve("users.txt"));
        assertThat(AuthManager.register("alice", "correct horse battery staple")).isTrue();
        assertThat(AuthManager.login("alice", "correct horse battery staple")).isTrue();
        assertThat(AuthManager.login("alice", "wrong password")).isFalse();
        assertThat(AuthManager.login("nobody", "anything")).isFalse();
    }

    @Test
    void rejectsDuplicateRegistration(@TempDir Path tmp) {
        AuthManager.setUserFilePath(tmp.resolve("users.txt"));
        assertThat(AuthManager.register("bob", "first")).isTrue();
        assertThat(AuthManager.register("bob", "second")).isFalse();
        // The original password should still work
        assertThat(AuthManager.login("bob", "first")).isTrue();
    }

    @Test
    void passwordStoredAsBCryptHashNotPlaintext(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("users.txt");
        AuthManager.setUserFilePath(file);
        AuthManager.register("carol", "supersecret123");
        String content = Files.readString(file);
        assertThat(content).doesNotContain("supersecret123");
        // BCrypt hashes start with $2 (the prefix may be $2a$, $2b$, $2y$ depending on version)
        assertThat(content).matches("(?s)carol:\\$2[aby]\\$.*");
    }

    @Test
    void persistsAcrossReload(@TempDir Path tmp) {
        Path file = tmp.resolve("users.txt");
        AuthManager.setUserFilePath(file);
        AuthManager.register("dave", "hunter2");
        // Simulate a server restart by re-pointing at the same file
        AuthManager.setUserFilePath(file);
        assertThat(AuthManager.login("dave", "hunter2")).isTrue();
    }

    @Test
    void rejectsInvalidUsernames(@TempDir Path tmp) {
        AuthManager.setUserFilePath(tmp.resolve("users.txt"));
        assertThat(AuthManager.register("", "x")).isFalse();
        assertThat(AuthManager.register("  ", "x")).isFalse();
        assertThat(AuthManager.register("has:colon", "x")).isFalse();
        assertThat(AuthManager.register("user", "")).isFalse();
    }
}
