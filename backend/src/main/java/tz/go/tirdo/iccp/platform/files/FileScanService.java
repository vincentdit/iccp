package tz.go.tirdo.iccp.platform.files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.config.IccpProperties;
import tz.go.tirdo.iccp.platform.db.DbSessionContext;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Processes private objects from logical quarantine and records an auditable scan result. */
@Service
public class FileScanService {
    private static final Logger log = LoggerFactory.getLogger(FileScanService.class);
    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final FileScanner scanner;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final int maxAttempts;

    public FileScanService(JdbcClient jdbc, FileStorage storage, FileScanner scanner, TransactionTemplate tx,
                           AuditService audit, IccpProperties props) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.scanner = scanner;
        this.tx = tx;
        this.audit = audit;
        this.maxAttempts = props.files().scanMaxAttempts();
    }

    public void enqueue(String objectKey) {
        jdbc.sql("INSERT INTO integ.file_scan (object_key, scanner) VALUES (:k, :s)")
                .param("k", objectKey).param("s", scanner.name()).update();
    }

    @Scheduled(fixedDelayString = "${iccp.files.scan-interval-ms:3000}")
    public void processDue() {
        DbSessionContext.runAs(DbSessionContext.system("file-scan"), () -> {
            List<String> keys = jdbc.sql("""
                    SELECT object_key FROM integ.file_scan
                     WHERE status IN ('PENDING','ERROR') AND attempts < :max
                       AND updated_at < now() - (attempts * interval '5 seconds')
                     ORDER BY id LIMIT 20
                    """).param("max", maxAttempts).query(String.class).list();
            keys.forEach(this::scanOne);
        });
    }

    private void scanOne(String key) {
        try (InputStream content = storage.open(key)) {
            FileScanner.ScanResult result = scanner.scan(content);
            if (!result.clean()) storage.delete(key);
            record(key, result.clean() ? "CLEAN" : "INFECTED", result.detail(), true);
        } catch (Exception e) {
            log.warn("Attachment scan failed key={} scanner={}: {}", key, scanner.name(), e.getMessage());
            record(key, "ERROR", e.getMessage(), false);
        }
    }

    private void record(String key, String status, String detail, boolean terminal) {
        String safe = detail == null ? null : detail.substring(0, Math.min(detail.length(), 500));
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                    UPDATE integ.file_scan SET status = :status, attempts = attempts + 1, result = :result,
                           scanned_at = CASE WHEN :terminal THEN now() ELSE scanned_at END, updated_at = now()
                     WHERE object_key = :key AND status IN ('PENDING','ERROR')
                    """).param("status", status).param("result", safe).param("terminal", terminal).param("key", key).update();
            audit.record("SEC", "FILE_SCAN_" + status, "stored_object", key, Map.of("scanner", scanner.name()));
        });
    }
}
