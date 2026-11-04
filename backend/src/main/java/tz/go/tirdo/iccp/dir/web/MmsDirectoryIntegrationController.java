package tz.go.tirdo.iccp.dir.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.iccp.dir.DirectoryQueries;
import tz.go.tirdo.iccp.platform.config.IccpProperties;
import tz.go.tirdo.iccp.platform.web.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@RestController
@RequestMapping("/api/v1/integrations/mms")
public class MmsDirectoryIntegrationController {
    private final DirectoryQueries directory;
    private final IccpProperties properties;

    public MmsDirectoryIntegrationController(DirectoryQueries directory, IccpProperties properties) {
        this.directory = directory;
        this.properties = properties;
    }

    @GetMapping("/directory")
    public List<DirectoryQueries.DirectoryExport> directory(
            @RequestHeader(value = "X-ICCP-Integration-Key", required = false) String suppliedKey) {
        String configuredKey = properties.integrations().mmsSyncSecret();
        if (configuredKey == null || configuredKey.isBlank() || suppliedKey == null
                || !MessageDigest.isEqual(configuredKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized();
        }
        return directory.exportForMms();
    }
}
