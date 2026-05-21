package org.example;

import org.example.clients.Client;
import org.example.servers.Server;

public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }
        switch (args[0].toLowerCase()) {
            case "server" -> Server.main(slice(args, 1));
            case "client" -> Client.main(slice(args, 1));
            default -> printUsage();
        }
    }

    private static String[] slice(String[] args, int start) {
        if (args == null || args.length <= start) {
            return new String[0];
        }
        String[] slice = new String[args.length - start];
        System.arraycopy(args, start, slice, 0, slice.length);
        return slice;
    }

    private static void printUsage() {
        System.out.println("Secure Chat");
        System.out.println("Usage:");
        System.out.println("  java -jar secure-chat.jar server");
        System.out.println("  java -jar secure-chat.jar client");
    }
}
