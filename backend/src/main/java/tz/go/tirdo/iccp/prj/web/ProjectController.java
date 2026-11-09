package tz.go.tirdo.iccp.prj.web;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.web.ApiException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/prj")
public class ProjectController {
    private final JdbcClient jdbc; private final AuditService audit;
    public ProjectController(JdbcClient jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}
    public record Workspace(UUID publicId,String code,String title,String type,String leadName,String unitName,LocalDate startsOn,LocalDate endsOn,String status,boolean confidential,int milestones,int overdueMilestones,OffsetDateTime updatedAt){}
    public record CreateWorkspace(String code,String title,String type,LocalDate startsOn,LocalDate endsOn,String fundingSource,boolean confidential){}
    public record Milestone(String title,LocalDate plannedOn){}
    public record State(String status){}

    @GetMapping("/workspaces")
    public List<Workspace> list(){
        long me=CurrentUser.get().staffId();
        return jdbc.sql("""
            SELECT w.public_id,w.code,w.title,w.ws_type type,concat_ws(' ',nullif(s.title,''),s.first_name,s.last_name) lead_name,
                   u.name_en unit_name,w.starts_on,w.ends_on,w.status,w.confidential,
                   (SELECT count(*)::int FROM prj.milestone m WHERE m.workspace_id=w.id) milestones,
                   (SELECT count(*)::int FROM prj.milestone m WHERE m.workspace_id=w.id AND m.planned_on<current_date AND m.status NOT IN ('DONE','CANCELLED')) overdue_milestones,
                   coalesce(w.updated_at,w.created_at) updated_at
              FROM prj.workspace w JOIN dir.staff_member s ON s.id=w.lead_staff_id JOIN dir.org_unit u ON u.id=w.org_unit_id
             WHERE NOT w.confidential OR w.lead_staff_id=:me OR EXISTS(SELECT 1 FROM prj.workspace_member x WHERE x.workspace_id=w.id AND x.staff_id=:me AND x.removed_at IS NULL)
             ORDER BY CASE w.status WHEN 'ACTIVE' THEN 0 WHEN 'ON_HOLD' THEN 1 ELSE 2 END,w.ends_on NULLS LAST
            """).param("me",me).query(Workspace.class).list();
    }

    @PostMapping("/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UUID create(@RequestBody CreateWorkspace in){
        CurrentUser.require("PROJECT_LEAD","DIRECTOR","EXECUTIVE");
        if(in.code()==null||in.code().isBlank()||in.title()==null||in.title().isBlank()||in.startsOn()==null)throw ApiException.badRequest("ICCP-PRJ-001","Code, title and start date are required.");
        String type=in.type()==null?"INTERNAL":in.type().toUpperCase(); if(!List.of("RESEARCH","CONSULTANCY","INTERNAL").contains(type))throw ApiException.badRequest("ICCP-PRJ-002","Invalid workspace type.");
        long me=CurrentUser.get().staffId();
        UUID id=jdbc.sql("""
            INSERT INTO prj.workspace(code,title,ws_type,lead_staff_id,org_unit_id,starts_on,ends_on,funding_source,confidential)
            VALUES(:code,:title,:type,:lead,:unit,:starts,:ends,:funding,:confidential) RETURNING public_id
            """).param("code",in.code().trim().toUpperCase()).param("title",in.title().trim()).param("type",type).param("lead",me)
                .param("unit",CurrentUser.get().orgUnitId()).param("starts",in.startsOn()).param("ends",in.endsOn())
                .param("funding",in.fundingSource()).param("confidential",in.confidential()).query(UUID.class).single();
        long wid=jdbc.sql("SELECT id FROM prj.workspace WHERE public_id=:id").param("id",id).query(Long.class).single();
        jdbc.sql("INSERT INTO prj.workspace_member(workspace_id,staff_id,member_role) VALUES(:w,:s,'LEAD')").param("w",wid).param("s",me).update();
        audit.record("PRJ","WORKSPACE_CREATED","workspace",id,Map.of("code",in.code().trim().toUpperCase()));
        return id;
    }

    @PostMapping("/workspaces/{id}/milestones")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public void milestone(@PathVariable UUID id,@RequestBody Milestone in){
        long w=managed(id); if(in.title()==null||in.title().isBlank()||in.plannedOn()==null)throw ApiException.badRequest("ICCP-PRJ-003","Milestone title and planned date are required.");
        jdbc.sql("INSERT INTO prj.milestone(workspace_id,title,planned_on) VALUES(:w,:title,:date)").param("w",w).param("title",in.title().trim()).param("date",in.plannedOn()).update();
        audit.record("PRJ","MILESTONE_CREATED","workspace",id,Map.of("title",in.title().trim()));
    }

    @PostMapping("/workspaces/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void status(@PathVariable UUID id,@RequestBody State in){
        long w=managed(id); String next=in.status()==null?"":in.status().toUpperCase();if(!List.of("ACTIVE","ON_HOLD","CLOSED").contains(next))throw ApiException.badRequest("ICCP-PRJ-004","Invalid status.");
        jdbc.sql("UPDATE prj.workspace SET status=:s,closed_at=CASE WHEN :s='CLOSED' THEN now() ELSE NULL END WHERE id=:id").param("s",next).param("id",w).update();
        audit.record("PRJ","WORKSPACE_STATUS_CHANGED","workspace",id,Map.of("status",next));
    }
    private long managed(UUID id){return jdbc.sql("SELECT id FROM prj.workspace WHERE public_id=:id AND lead_staff_id=:me AND status<>'CLOSED'").param("id",id).param("me",CurrentUser.get().staffId()).query(Long.class).optional().orElseThrow(ApiException::notFound);}
}
