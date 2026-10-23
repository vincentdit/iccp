package tz.go.tirdo.iccp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TIRDO Integrated Communication &amp; Collaboration Platform (ICCP).
 *
 * <p>Modular monolith (Doc 05 ADR-01). Release 1 modules: {@code sec}, {@code dir}, {@code ntf},
 * {@code com}, {@code fin}, on top of the shared {@code platform} layer. Modules talk to each other
 * only through their {@code api} packages and the transactional outbox; {@code ModuleBoundaryTest}
 * enforces this.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class IccpApplication {

    public static void main(String[] args) {
        SpringApplication.run(IccpApplication.class, args);
    }
}
