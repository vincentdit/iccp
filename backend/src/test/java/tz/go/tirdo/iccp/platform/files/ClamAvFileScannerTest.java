package tz.go.tirdo.iccp.platform.files;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ClamAvFileScannerTest {
    @Test
    void streamsContentAndAcceptsCleanResponse() throws Exception {
        Exchange exchange = exchange("stream: OK");
        FileScanner.ScanResult result = exchange.scanner().scan(new ByteArrayInputStream("safe".getBytes(StandardCharsets.UTF_8)));
        exchange.thread().join();
        assertThat(result.clean()).isTrue();
        assertThat(exchange.received().get()).isEqualTo("safe");
    }

    @Test
    void reportsInfectedContent() throws Exception {
        Exchange exchange = exchange("stream: Eicar-Signature FOUND");
        FileScanner.ScanResult result = exchange.scanner().scan(new ByteArrayInputStream("virus".getBytes(StandardCharsets.UTF_8)));
        exchange.thread().join();
        assertThat(result.clean()).isFalse();
        assertThat(result.detail()).contains("FOUND");
    }

    private Exchange exchange(String response) throws Exception {
        ServerSocket server = new ServerSocket(0);
        AtomicReference<String> received = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try (server; var socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                byte[] command = in.readNBytes(10);
                assertThat(new String(command, StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                var body = new java.io.ByteArrayOutputStream();
                int size;
                while ((size = in.readInt()) > 0) body.write(in.readNBytes(size));
                received.set(body.toString(StandardCharsets.UTF_8));
                socket.getOutputStream().write((response + "\0").getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return new Exchange(new ClamAvFileScanner("127.0.0.1", server.getLocalPort(), 1000, 1000), thread, received);
    }

    private record Exchange(ClamAvFileScanner scanner, Thread thread, AtomicReference<String> received) {}
}
