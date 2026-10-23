package tz.go.tirdo.iccp.platform.files;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.go.tirdo.iccp.platform.config.IccpProperties;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Configuration
public class FileStorageConfig {

    /** Content types accepted for attachments (XFR-03 allow-list). */
    public static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/webp", "text/plain", "text/csv",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    @Bean
    public FileStorage fileStorage(IccpProperties props) {
        IccpProperties.Files f = props.files();
        if ("minio".equalsIgnoreCase(f.store())) {
            MinioClient client = MinioClient.builder().endpoint(f.minioEndpoint()).credentials(f.minioAccessKey(), f.minioSecretKey()).build();
            return new MinioStorage(client, f.minioBucket());
        }
        return new LocalStorage(Path.of(f.localDir()));
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String newKey(String prefix, String fileName) {
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot >= fileName.length() - 6) {
            ext = fileName.substring(dot).toLowerCase().replaceAll("[^a-z0-9.]", "");
        }
        return prefix + "/" + LocalDate.now() + "/" + UUID.randomUUID() + ext;
    }

    /** Filesystem store for development and tests. */
    static final class LocalStorage implements FileStorage {
        private final Path root;

        LocalStorage(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public StoredFile put(String keyPrefix, String fileName, String contentType, byte[] content) {
            String key = newKey(keyPrefix, fileName);
            Path target = resolve(key);
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, content);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new StoredFile(key, fileName, contentType, content.length, sha256(content));
        }

        @Override
        public InputStream open(String objectKey) {
            try {
                return Files.newInputStream(resolve(objectKey));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void delete(String objectKey) {
            try {
                Files.deleteIfExists(resolve(objectKey));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private Path resolve(String key) {
            Path p = root.resolve(key).normalize();
            if (!p.startsWith(root)) {
                throw new IllegalArgumentException("Invalid object key");
            }
            return p;
        }
    }

    /** MinIO store (private bucket, created on first use). */
    static final class MinioStorage implements FileStorage {
        private final MinioClient client;
        private final String bucket;
        private volatile boolean bucketChecked;

        MinioStorage(MinioClient client, String bucket) {
            this.client = client;
            this.bucket = bucket;
        }

        @Override
        public StoredFile put(String keyPrefix, String fileName, String contentType, byte[] content) {
            String key = newKey(keyPrefix, fileName);
            try {
                ensureBucket();
                client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                        .stream(new ByteArrayInputStream(content), content.length, -1).contentType(contentType).build());
            } catch (Exception e) {
                throw new IllegalStateException("Could not store file", e);
            }
            return new StoredFile(key, fileName, contentType, content.length, sha256(content));
        }

        @Override
        public InputStream open(String objectKey) {
            try {
                return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
            } catch (Exception e) {
                throw new IllegalStateException("Could not read file", e);
            }
        }

        @Override
        public void delete(String objectKey) {
            try {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
            } catch (Exception e) {
                throw new IllegalStateException("Could not delete file", e);
            }
        }

        private void ensureBucket() throws Exception {
            if (!bucketChecked) {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
                bucketChecked = true;
            }
        }
    }
}
