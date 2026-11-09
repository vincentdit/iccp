package tz.go.tirdo.iccp.ana.web;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import tz.go.tirdo.iccp.platform.security.CurrentUser;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/ana")
public class AnalyticsController {
    private final JdbcClient jdbc;
    public AnalyticsController(JdbcClient jdbc){this.jdbc=jdbc;}
    public record Metric(String code,String label,Number value,String unit){}
    public record Dashboard(OffsetDateTime asAt,List<Metric> metrics){}

    @GetMapping("/dashboard")
    public Dashboard dashboard(){
        CurrentUser.get();
        List<Metric> metrics=jdbc.sql("SELECT code,label,value,unit FROM ana.current_dashboard ORDER BY sort_order").query(Metric.class).list();
        return new Dashboard(OffsetDateTime.now(),metrics);
    }
}
