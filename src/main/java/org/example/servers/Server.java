package org.example.servers;

import org.example.TlsContextFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;

/**
 * TLS 1.3 chat server. Accepts connections, spawns a {@link CommunicationHandler}
 * per client.
 */
public class Server {

    private final int port;
    private final SSLContext sslContext;

    public Server(int port, SSLContext sslContext) {
        this.port = port;
        this.sslContext = sslContext;
    }

    public void start() throws IOException {
        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        try (SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(port)) {
            serverSocket.setEnabledProtocols(new String[] {"TLSv1.3"});
            System.out.println("Server listening on " + port + " (TLS 1.3)");
            while (true) {
                Socket socket = serverSocket.accept();
                try {
                    new Thread(new CommunicationHandler(socket)).start();
                } catch (IOException e) {
                    System.err.println("Failed to start client handler: " + e.getMessage());
                    socket.close();
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("SECURECHAT_PORT", "8888"));
        Path keystore = Path.of(env("SECURECHAT_KEYSTORE", "certs/server.p12"));
        char[] kspass = env("SECURECHAT_KEYSTORE_PASSWORD", "changeit").toCharArray();
        Path usersFile = Path.of(env("SECURECHAT_USERS_FILE", "users.txt"));

        AuthManager.setUserFilePath(usersFile);
        SSLContext ctx = TlsContextFactory.serverContext(keystore, kspass);
        new Server(port, ctx).start();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
