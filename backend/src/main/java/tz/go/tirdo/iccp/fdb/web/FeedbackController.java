package tz.go.tirdo.iccp.fdb.web;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.web.ApiException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fdb")
public class FeedbackController {
    private final JdbcClient jdbc; private final AuditService audit;
    public FeedbackController(JdbcClient jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}
    public record Suggestion(UUID publicId,String category,String description,String status,String response,OffsetDateTime createdAt,OffsetDateTime updatedAt){}
    public record CreateSuggestion(String category,String description){}
    public record Survey(UUID publicId,String title,String mode,OffsetDateTime opensAt,OffsetDateTime closesAt,String status){}

    @GetMapping("/suggestions")
    public List<Suggestion> suggestions(){return jdbc.sql("""
        SELECT public_id,category,description,status,response,created_at,updated_at FROM fdb.suggestion
         WHERE staff_id=:me ORDER BY created_at DESC
        """).param("me",CurrentUser.get().staffId()).query(Suggestion.class).list();}

    @PostMapping("/suggestions")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UUID create(@RequestBody CreateSuggestion in){
        if(in.category()==null||in.category().isBlank()||in.description()==null||in.description().isBlank())throw ApiException.badRequest("ICCP-FDB-001","Category and description are required.");
        UUID id=jdbc.sql("INSERT INTO fdb.suggestion(staff_id,category,description) VALUES(:me,:category,:description) RETURNING public_id")
                .param("me",CurrentUser.get().staffId()).param("category",in.category().trim()).param("description",in.description().trim()).query(UUID.class).single();
        audit.record("FDB","SUGGESTION_CREATED","suggestion",id,Map.of("category",in.category().trim()));
        return id;
    }

    @GetMapping("/surveys")
    public List<Survey> surveys(){return jdbc.sql("""
        SELECT public_id,title_en title,mode,opens_at,closes_at,status FROM fdb.survey
         WHERE status='OPEN' AND now() BETWEEN opens_at AND closes_at ORDER BY closes_at
        """).query(Survey.class).list();}

    @GetMapping("/protected-enabled")
    public Map<String,Boolean> protectedEnabled(){
        return Map.of("enabled",false);
    }
}
