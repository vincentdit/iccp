package tz.go.tirdo.iccp.trn.web;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.web.ApiException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trn")
public class TrainingController {
    private final JdbcClient jdbc; private final AuditService audit;
    public TrainingController(JdbcClient jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}
    public record Offering(UUID publicId,String title,String provider,String mode,LocalDate startsOn,LocalDate endsOn,String location,Integer seats,LocalDate deadline,BigDecimal indicativeCost,String status,String applicationStatus){}
    public record Apply(String justification){}
    public record CreateOffering(String title,String provider,String mode,LocalDate startsOn,LocalDate endsOn,String location,Integer seats,LocalDate deadline,BigDecimal indicativeCost,String teltpCourseRef){}

    @GetMapping("/offerings")
    public List<Offering> offerings(){return jdbc.sql("""
        SELECT o.public_id,o.title,o.provider,o.mode,o.starts_on,o.ends_on,o.location,o.seats,o.application_deadline deadline,o.indicative_cost,o.status,
               (SELECT a.status FROM trn.training_application a WHERE a.offering_id=o.id AND a.staff_id=:me) application_status
          FROM trn.training_offering o WHERE o.status IN ('OPEN','COMPLETED') ORDER BY o.starts_on NULLS LAST,o.title
        """).param("me",CurrentUser.get().staffId()).query(Offering.class).list();}

    @PostMapping("/offerings/{id}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public void apply(@PathVariable UUID id,@RequestBody Apply in){
        if(in.justification()==null||in.justification().isBlank())throw ApiException.badRequest("ICCP-TRN-001","A justification is required.");
        long offering=jdbc.sql("SELECT id FROM trn.training_offering WHERE public_id=:id AND status='OPEN' AND (application_deadline IS NULL OR application_deadline>=current_date)")
                .param("id",id).query(Long.class).optional().orElseThrow(ApiException::notFound);
        try{jdbc.sql("INSERT INTO trn.training_application(offering_id,staff_id,justification) VALUES(:o,:s,:j)")
                .param("o",offering).param("s",CurrentUser.get().staffId()).param("j",in.justification().trim()).update();}
        catch(Exception e){throw ApiException.conflict("ICCP-TRN-002","You have already applied for this training.");}
        audit.record("TRN","TRAINING_APPLIED","training_offering",id,Map.of());
    }

    @PostMapping("/offerings")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UUID create(@RequestBody CreateOffering in){
        CurrentUser.require("TRAINING_COORDINATOR","EXECUTIVE");
        if(in.title()==null||in.title().isBlank()||in.provider()==null||in.provider().isBlank())throw ApiException.badRequest("ICCP-TRN-003","Title and provider are required.");
        String mode=in.mode()==null?"INTERNAL":in.mode().toUpperCase();if(!List.of("INTERNAL","EXTERNAL","ONLINE_TELTP").contains(mode))throw ApiException.badRequest("ICCP-TRN-004","Invalid delivery mode.");
        if(mode.equals("ONLINE_TELTP")&&(in.teltpCourseRef()==null||in.teltpCourseRef().isBlank()))throw ApiException.badRequest("ICCP-TRN-005","TeLTP course reference is required.");
        UUID id=jdbc.sql("""
            INSERT INTO trn.training_offering(title,provider,mode,starts_on,ends_on,location,seats,application_deadline,indicative_cost,teltp_course_ref,status)
            VALUES(:title,:provider,:mode,:starts,:ends,:location,:seats,:deadline,:cost,:teltp,'OPEN') RETURNING public_id
            """).param("title",in.title().trim()).param("provider",in.provider().trim()).param("mode",mode).param("starts",in.startsOn()).param("ends",in.endsOn())
                .param("location",in.location()).param("seats",in.seats()).param("deadline",in.deadline()).param("cost",in.indicativeCost()).param("teltp",in.teltpCourseRef()).query(UUID.class).single();
        audit.record("TRN","TRAINING_OFFERING_CREATED","training_offering",id,Map.of("mode",mode));
        return id;
    }
}
