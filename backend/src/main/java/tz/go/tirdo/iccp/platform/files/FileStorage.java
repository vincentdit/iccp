package tz.go.tirdo.iccp.platform.files;

import java.io.InputStream;

/**
 * Private object storage for attachments (Doc 05 ADR-07). Production uses MinIO; development can
 * use the local filesystem. Objects are never exposed by public URL; the API streams them after
 * an authorisation check.
 */
public interface FileStorage {

    StoredFile put(String keyPrefix, String fileName, String contentType, byte[] content);

    InputStream open(String objectKey);

    void delete(String objectKey);

    record StoredFile(String objectKey, String fileName, String contentType, long sizeBytes, String sha256) {
    }
}
