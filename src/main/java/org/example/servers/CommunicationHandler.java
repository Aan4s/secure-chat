package org.example.servers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.Vector;

/**
 * One instance per connected client. Handles auth, then routes messages and
 * file chunks to other connected clients.
 *
 * <p>Wire format (all text, one event per line, sent over TLS):
 * <pre>
 *   AUTH#LOGIN#user#pwd            client → server, first line only
 *   AUTH#REGISTER#user#pwd         client → server, first line only
 *   AUTH_SUCCESS  /  AUTH_FAILED   server → client response
 *
 *   &lt;recipient&gt;:&lt;body&gt;       client → server: send chat message
 *   From &lt;sender&gt;: &lt;body&gt;    server → recipient: incoming message
 *
 *   @file_begin:&lt;name&gt;:&lt;filename&gt;:&lt;size&gt;    file metadata
 *   @file_chunk:&lt;name&gt;:&lt;base64&gt;                a single chunk
 *   @file_end:&lt;name&gt;                              end-of-file marker
 *
 *   BYE                            client → server: close session
 * </pre>
 *
 * <p>On outgoing lines the {@code name} field is the recipient; on incoming lines
 * the server rewrites it to the sender's name so the receiver knows who it's from.
 */
public class CommunicationHandler implements Runnable {

    public static final Vector<CommunicationHandler> loggedInClients = new Vector<>();

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private String name = "?";
    private boolean loggedIn = false;

    public CommunicationHandler(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.out = new PrintWriter(socket.getOutputStream(), true);
    }

    public String getName() {
        return name;
    }

    public PrintWriter getOut() {
        return out;
    }

    @Override
    public void run() {
        try {
            if (!authenticate()) {
                return;
            }
            loggedInClients.add(this);
            loggedIn = true;
            System.out.println("Authenticated: " + name + " @ " + LocalDateTime.now());
            sendWelcome();
            handleChat();
        } catch (IOException e) {
            System.err.println("Connection error for " + name + ": " + e.getMessage());
        } finally {
            loggedInClients.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            System.out.println(name + " disconnected @ " + LocalDateTime.now());
        }
    }

    private boolean authenticate() throws IOException {
        String line = in.readLine();
        if (line == null || !line.startsWith("AUTH#")) {
            out.println("AUTH_FAILED: invalid format");
            return false;
        }
        String[] parts = line.split("#", 4);
        if (parts.length < 4) {
            out.println("AUTH_FAILED: invalid format");
            return false;
        }
        String mode = parts[1];
        String username = parts[2];
        String password = parts[3];
        boolean ok = switch (mode.toUpperCase()) {
            case "LOGIN" -> AuthManager.login(username, password);
            case "REGISTER" -> AuthManager.register(username, password);
            default -> false;
        };
        if (!ok) {
            out.println("AUTH_FAILED");
            System.out.println("Failed auth: " + username + " @ " + LocalDateTime.now());
            return false;
        }
        this.name = username;
        out.println("AUTH_SUCCESS");
        return true;
    }

    private void sendWelcome() {
        out.println(">>> Welcome, " + name + "!");
        out.println(">>> Send a message:  <recipient>:<message>");
        out.println(">>> Send a file:     @file:<recipient>:<local file path>");
        out.println(">>> Commands:        /help    /who    /quit (or 'bye')");
        out.println(">>> Currently online: " + loggedInClients);
    }

    private void handleChat() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals("BYE")) {
                break;
            }
            if (line.equals("WHO")) {
                handleWho();
                continue;
            }
            if (line.startsWith("@file_begin:")
                    || line.startsWith("@file_chunk:")
                    || line.startsWith("@file_end:")) {
                relayFileLine(line);
            } else {
                handleMessage(line);
            }
        }
    }

    private void handleWho() {
        StringBuilder sb = new StringBuilder(">> Online: ");
        boolean first = true;
        for (CommunicationHandler h : loggedInClients) {
            if (h.loggedIn) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(h.name);
                first = false;
            }
        }
        if (first) {
            sb.append("(no one)");
        }
        out.println(sb.toString());
    }

    /**
     * Routes a file-protocol line to its recipient. The second colon-separated field
     * is the recipient; we rewrite it to our own name so the recipient knows who sent it.
     */
    private void relayFileLine(String line) {
        String[] parts = line.split(":", 4);
        if (parts.length < 2) {
            return;
        }
        String recipient = parts[1];
        CommunicationHandler r = findRecipient(recipient);
        if (r == null) {
            out.println("@file_err:" + recipient + " is not online");
            return;
        }
        // Reconstruct with our own name as the second field
        StringBuilder rebuilt = new StringBuilder(parts[0]).append(':').append(this.name);
        for (int i = 2; i < parts.length; i++) {
            rebuilt.append(':').append(parts[i]);
        }
        r.out.println(rebuilt.toString());
    }

    private void handleMessage(String line) {
        if (line.isBlank()) {
            return;
        }
        int sep = line.indexOf(':');
        if (sep < 0) {
            out.println(">>> Format: <recipient>:<message>");
            return;
        }
        String recipientName = line.substring(0, sep).trim();
        String body = line.substring(sep + 1);
        CommunicationHandler r = findRecipient(recipientName);
        if (r == null) {
            out.println(">> Recipient not online: " + recipientName);
            return;
        }
        r.out.println("From " + this.name + ": " + body);
        out.println(">> Sent to " + recipientName);
    }

    private CommunicationHandler findRecipient(String username) {
        for (CommunicationHandler h : loggedInClients) {
            if (h != this && h.loggedIn && username.equalsIgnoreCase(h.name)) {
                return h;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return name;
    }
}
