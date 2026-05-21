package org.example.clients;

import org.example.TlsContextFactory;
import org.example.util.Filenames;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * TLS chat client. Connects, authenticates, then runs a sender (stdin → server) and a
 * receiver (server → stdout) on two threads.
 */
public class Client implements Runnable {

    /** Hard cap on incoming and outgoing file sizes. */
    public static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    /** Raw chunk size before base64 encoding; keeps lines well under typical buffer limits. */
    private static final int CHUNK_SIZE = 48 * 1024;

    private final String clientName;
    private final Socket socket;
    private final Scanner stdin;
    private final PrintWriter out;
    private final BufferedReader in;
    private final Map<String, FileReceive> incomingFiles = new HashMap<>();

    public Client(Socket socket, String clientName) throws IOException {
        this.socket = socket;
        this.clientName = clientName;
        this.stdin = new Scanner(System.in);
        this.out = new PrintWriter(socket.getOutputStream(), true);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }

    @Override
    public void run() {
        Thread sender = senderThread();
        Thread receiver = receiverThread();
        sender.start();
        receiver.start();
        try {
            sender.join();
            receiver.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }
    }

    private Thread senderThread() {
        return new Thread(() -> {
            try {
                while (stdin.hasNextLine()) {
                    String line = stdin.nextLine();
                    if (line.isBlank()) {
                        continue;
                    }
                    if (line.equalsIgnoreCase("bye") || line.equalsIgnoreCase("/quit")) {
                        out.println("BYE");
                        socket.close(); // unblock receiver
                        return;
                    }
                    if (line.equalsIgnoreCase("/help")) {
                        printHelp();
                        continue;
                    }
                    if (line.equalsIgnoreCase("/who")) {
                        out.println("WHO");
                        continue;
                    }
                    if (line.startsWith("@file:")) {
                        String[] parts = line.split(":", 3);
                        if (parts.length < 3) {
                            System.out.println("Usage: @file:<recipient>:<local file path>");
                            continue;
                        }
                        sendFile(parts[1], parts[2]);
                        continue;
                    }
                    out.println(line);
                }
            } catch (Exception e) {
                System.err.println("Sender error: " + e.getMessage());
            }
        }, "sender");
    }

    private void printHelp() {
        long maxMib = MAX_FILE_SIZE / (1024 * 1024);
        System.out.println();
        System.out.println("=== Commands ===");
        System.out.println("  <recipient>:<message>           Send a message");
        System.out.println("  @file:<recipient>:<path>        Send a file (max " + maxMib + " MiB)");
        System.out.println("  /who                            List currently online users");
        System.out.println("  /help                           Show this help");
        System.out.println("  /quit  or  bye                  Disconnect and exit");
        System.out.println();
        System.out.println("Received files are saved into a 'downloads/' folder");
        System.out.println("created in the directory where you started this client.");
        System.out.println();
    }

    private Thread receiverThread() {
        return new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("@file_begin:")) {
                        handleFileBegin(line);
                    } else if (line.startsWith("@file_chunk:")) {
                        handleFileChunk(line);
                    } else if (line.startsWith("@file_end:")) {
                        handleFileEnd(line);
                    } else if (line.startsWith("@file_err:")) {
                        System.out.println("File error: " + line.substring("@file_err:".length()));
                    } else {
                        System.out.println(line);
                    }
                }
            } catch (IOException e) {
                // Expected on graceful shutdown via socket.close() from sender
            }
        }, "receiver");
    }

    /* ---------------- File sending ---------------- */

    private void sendFile(String recipient, String path) {
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            System.out.println("File not found: " + path);
            return;
        }
        if (file.length() > MAX_FILE_SIZE) {
            System.out.println("File too large (max " + MAX_FILE_SIZE + " bytes)");
            return;
        }
        String safeName;
        try {
            safeName = Filenames.sanitize(file.getName());
        } catch (IllegalArgumentException e) {
            System.out.println("Bad filename: " + e.getMessage());
            return;
        }
        try (InputStream is = Files.newInputStream(file.toPath())) {
            out.println("@file_begin:" + recipient + ":" + safeName + ":" + file.length());
            byte[] buf = new byte[CHUNK_SIZE];
            int n;
            Base64.Encoder b64 = Base64.getEncoder();
            while ((n = is.read(buf)) != -1) {
                byte[] toEncode = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                out.println("@file_chunk:" + recipient + ":" + b64.encodeToString(toEncode));
            }
            out.println("@file_end:" + recipient);
            System.out.println("Sent " + safeName + " to " + recipient);
        } catch (IOException e) {
            System.err.println("File send failed: " + e.getMessage());
        }
    }

    /* ---------------- File receiving ---------------- */

    private static final class FileReceive {
        final String localPath;
        final long expectedSize;
        long writtenSoFar;
        final OutputStream out;

        FileReceive(String localPath, long expectedSize, OutputStream out) {
            this.localPath = localPath;
            this.expectedSize = expectedSize;
            this.out = out;
        }
    }

    private void handleFileBegin(String line) {
        String[] parts = line.split(":", 4);
        if (parts.length != 4) {
            return;
        }
        String sender = parts[1];
        String filenameRaw = parts[2];
        long size;
        try {
            size = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return;
        }
        if (size < 0 || size > MAX_FILE_SIZE) {
            System.out.println("Refusing file from " + sender + " (size " + size + " out of range)");
            return;
        }
        try {
            String safe = Filenames.sanitize(filenameRaw);
            Path dir = Path.of("downloads");
            Files.createDirectories(dir);
            Path target = dir.resolve(safe);
            if (Files.exists(target)) {
                target = dir.resolve(System.currentTimeMillis() + "_" + safe);
            }
            OutputStream os = Files.newOutputStream(target);
            incomingFiles.put(sender, new FileReceive(target.toAbsolutePath().toString(), size, os));
            System.out.println("Receiving " + safe + " from " + sender + " (" + size + " bytes)");
        } catch (Exception e) {
            System.err.println("Cannot start file receive: " + e.getMessage());
        }
    }

    private void handleFileChunk(String line) {
        int p1 = line.indexOf(':');
        int p2 = line.indexOf(':', p1 + 1);
        if (p1 < 0 || p2 < 0) {
            return;
        }
        String sender = line.substring(p1 + 1, p2);
        String b64 = line.substring(p2 + 1);
        FileReceive fr = incomingFiles.get(sender);
        if (fr == null) {
            return;
        }
        try {
            byte[] data = Base64.getDecoder().decode(b64);
            // Enforce the declared size as a hard limit
            if (fr.writtenSoFar + data.length > fr.expectedSize) {
                long allowed = fr.expectedSize - fr.writtenSoFar;
                if (allowed > 0) {
                    fr.out.write(data, 0, (int) allowed);
                }
                fr.out.close();
                incomingFiles.remove(sender);
                System.out.println("File from " + sender + " truncated (declared size exceeded)");
                return;
            }
            fr.out.write(data);
            fr.writtenSoFar += data.length;
        } catch (Exception e) {
            System.err.println("Bad chunk from " + sender + ": " + e.getMessage());
        }
    }

    private void handleFileEnd(String line) {
        int p1 = line.indexOf(':');
        if (p1 < 0) {
            return;
        }
        String sender = line.substring(p1 + 1);
        FileReceive fr = incomingFiles.remove(sender);
        if (fr == null) {
            return;
        }
        try {
            fr.out.close();
            System.out.println("=== File received from " + sender + " ===");
            System.out.println("    Saved to: " + fr.localPath);
        } catch (IOException e) {
            System.err.println("Close failed: " + e.getMessage());
        }
    }

    /* ---------------- Lifecycle ---------------- */

    private void close() {
        try { stdin.close(); } catch (Exception ignored) {}
        try { out.close(); } catch (Exception ignored) {}
        try { in.close(); } catch (Exception ignored) {}
        try { socket.close(); } catch (Exception ignored) {}
        for (FileReceive fr : incomingFiles.values()) {
            try { fr.out.close(); } catch (Exception ignored) {}
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner stdin = new Scanner(System.in);
        System.out.println("(1) Login or (2) Register?");
        String action;
        while (true) {
            action = stdin.nextLine().trim();
            if (action.equals("1") || action.equals("2")) {
                break;
            }
            System.out.println("Please enter 1 or 2");
        }
        System.out.print("Username: "); String username = stdin.nextLine().trim();
        System.out.print("Password: "); String password = stdin.nextLine();
        System.out.print("Host (default localhost): "); String host = stdin.nextLine().trim();
        if (host.isBlank()) {
            host = "localhost";
        }
        System.out.print("Port (default 8888): "); String portStr = stdin.nextLine().trim();
        int port = portStr.isBlank() ? 8888 : Integer.parseInt(portStr);

        Path truststore = Path.of(env("SECURECHAT_TRUSTSTORE", "certs/client-truststore.p12"));
        char[] tspass = env("SECURECHAT_TRUSTSTORE_PASSWORD", "changeit").toCharArray();
        SSLContext ctx = TlsContextFactory.clientContext(truststore, tspass);
        SSLSocketFactory factory = ctx.getSocketFactory();

        SSLSocket socket = (SSLSocket) factory.createSocket(host, port);
        socket.setEnabledProtocols(new String[] {"TLSv1.3"});
        socket.startHandshake();

        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader sIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        out.println("AUTH#" + (action.equals("1") ? "LOGIN" : "REGISTER") + "#" + username + "#" + password);
        String resp = sIn.readLine();
        if (resp == null || !resp.equalsIgnoreCase("AUTH_SUCCESS")) {
            System.out.println("Authentication failed: " + resp);
            socket.close();
            return;
        }
        System.out.println("Connected.");
        new Client(socket, username).run();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
