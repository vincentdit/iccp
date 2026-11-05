package tz.go.tirdo.iccp.platform.files;

import java.io.InputStream;

/** Streams a private object to the configured malware scanner. */
public interface FileScanner {
    ScanResult scan(InputStream content) throws Exception;
    String name();

    record ScanResult(boolean clean, String detail) {
        public static ScanResult clean(String detail) { return new ScanResult(true, detail); }
        public static ScanResult infected(String detail) { return new ScanResult(false, detail); }
    }
}
