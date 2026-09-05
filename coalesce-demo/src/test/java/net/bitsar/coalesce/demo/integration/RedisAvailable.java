package net.bitsar.coalesce.demo.integration;

import java.net.InetSocketAddress;
import java.net.Socket;

/** Integration tests need a real Redis; without one they skip rather than fail the build. */
final class RedisAvailable {

    private RedisAvailable() {
    }

    static boolean check() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
