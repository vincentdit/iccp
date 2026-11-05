package tz.go.tirdo.iccp.platform.files;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** ClamAV clamd INSTREAM client; content is scanned without writing a temporary file. */
final class ClamAvFileScanner implements FileScanner {
    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    ClamAvFileScanner(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public ScanResult scan(InputStream content) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = content.read(buffer)) >= 0) {
                if (read == 0) continue;
                out.writeInt(read);
                out.write(buffer, 0, read);
            }
            out.writeInt(0);
            out.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            int b;
            while ((b = socket.getInputStream().read()) >= 0 && b != 0 && b != '\n') response.write(b);
            String result = response.toString(StandardCharsets.UTF_8).trim();
            if (result.endsWith("OK")) return ScanResult.clean(result);
            if (result.contains("FOUND")) return ScanResult.infected(result);
            throw new IllegalStateException("Unexpected ClamAV response: " + result);
        }
    }

    @Override
    public String name() { return "clamav"; }
}
