package tz.go.tirdo.iccp.platform.files;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.go.tirdo.iccp.platform.config.IccpProperties;

import java.io.InputStream;

@Configuration
public class FileScannerConfig {
    @Bean
    FileScanner fileScanner(IccpProperties props) {
        IccpProperties.Files f = props.files();
        if ("clamav".equalsIgnoreCase(f.scanProvider())) {
            return new ClamAvFileScanner(f.clamavHost(), f.clamavPort(), f.scanConnectTimeoutMs(), f.scanReadTimeoutMs());
        }
        return new FileScanner() {
            public ScanResult scan(InputStream content) { return ScanResult.clean("scanner disabled"); }
            public String name() { return "disabled"; }
        };
    }
}
