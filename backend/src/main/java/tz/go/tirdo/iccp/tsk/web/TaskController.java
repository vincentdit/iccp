package tz.go.tirdo.iccp.tsk.web;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.security.IccpPrincipal;
import tz.go.tirdo.iccp.platform.web.ApiException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tsk")
public class TaskController {
    private final JdbcClient jdbc;
    private final AuditService audit;

    public TaskController(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record TaskRow(UUID publicId, String referenceNo, String title, String description, String source,
                          String priority, String issuerName, String assigneeName, String verifierName,
                          LocalDate dueOn, String status, int progressPct, boolean evidenceRequired,
                          OffsetDateTime createdAt, boolean canAct) {}
    public record CreateTask(String title, String description, String source, String priority,
                             UUID assigneeStaffPublicId, UUID verifierStaffPublicId, LocalDate dueOn,
                             boolean evidenceRequired) {}
    public record Transition(String status, Integer progressPct, String comment) {}

    @GetMapping("/tasks")
    public List<TaskRow> tasks(@RequestParam(defaultValue = "mine") String view) {
        IccpPrincipal me = CurrentUser.get();
        String scope = switch (view) {
            case "issued" -> "t.issuer_staff_id=:me";
            case "unit" -> "t.assignee_unit_id=:unit";
            default -> "(t.assignee_staff_id=:me OR t.verifier_staff_id=:me OR t.issuer_staff_id=:me)";
        };
        return jdbc.sql("""
                SELECT t.public_id,t.reference_no,t.title,t.description,t.source,t.priority,
                       concat_ws(' ',nullif(i.title,''),i.first_name,i.last_name) issuer_name,
                       concat_ws(' ',nullif(a.title,''),a.first_name,a.last_name) assignee_name,
                       concat_ws(' ',nullif(v.title,''),v.first_name,v.last_name) verifier_name,
                       t.due_on,t.status,t.progress_pct,t.evidence_required,t.created_at,
                       (t.assignee_staff_id=:me OR t.verifier_staff_id=:me OR t.issuer_staff_id=:me) can_act
                  FROM tsk.task t JOIN dir.staff_member i ON i.id=t.issuer_staff_id
                  LEFT JOIN dir.staff_member a ON a.id=t.assignee_staff_id
                  LEFT JOIN dir.staff_member v ON v.id=t.verifier_staff_id
                 WHERE """ + scope + " ORDER BY CASE WHEN t.status IN ('CLOSED','CANCELLED') THEN 1 ELSE 0 END,t.due_on")
                .param("me", me.staffId()).param("unit", me.orgUnitId()).query(TaskRow.class).list();
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UUID create(@RequestBody CreateTask in) {
        IccpPrincipal me = CurrentUser.get();
        CurrentUser.require("LINE_MANAGER", "DIRECTOR", "EXECUTIVE", "MEETING_SECRETARY", "PROJECT_LEAD");
        if (in.title() == null || in.title().isBlank() || in.dueOn() == null || in.dueOn().isBefore(LocalDate.now())) {
            throw ApiException.badRequest("ICCP-TSK-001", "Title and a current or future due date are required.");
        }
        long assignee = staffId(in.assigneeStaffPublicId());
        Long verifier = in.verifierStaffPublicId() == null ? me.staffId() : staffId(in.verifierStaffPublicId());
        if (assignee == verifier) throw ApiException.badRequest("ICCP-TSK-002", "Assignee and verifier must be different.");
        String source = allowed(in.source(), List.of("DIRECTIVE","MEETING_RESOLUTION","AD_HOC","PROJECT","EXTERNAL_SYSTEM","REQUEST"), "AD_HOC");
        String priority = allowed(in.priority(), List.of("LOW","NORMAL","HIGH","CRITICAL"), "NORMAL");
        String ref = "TSK-" + LocalDate.now().getYear() + "-" + String.format("%06d",
                jdbc.sql("SELECT nextval('core.reference_seq')").query(Long.class).single());
        UUID id = jdbc.sql("""
                INSERT INTO tsk.task(reference_no,title,description,source,priority,issuer_staff_id,assignee_staff_id,verifier_staff_id,due_on,evidence_required)
                VALUES(:ref,:title,:description,:source,:priority,:issuer,:assignee,:verifier,:due,:evidence) RETURNING public_id
                """).param("ref",ref).param("title",in.title().trim()).param("description",in.description())
                .param("source",source).param("priority",priority).param("issuer",me.staffId()).param("assignee",assignee)
                .param("verifier",verifier).param("due",in.dueOn()).param("evidence",in.evidenceRequired()).query(UUID.class).single();
        audit.record("TSK","TASK_CREATED","task",id, Map.of("referenceNo",ref));
        return id;
    }

    @PostMapping("/tasks/{id}/transition")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void transition(@PathVariable UUID id, @RequestBody Transition in) {
        IccpPrincipal me = CurrentUser.get();
        Object[] row = jdbc.sql("SELECT id,status,assignee_staff_id,issuer_staff_id,verifier_staff_id,evidence_required FROM tsk.task WHERE public_id=:id")
                .param("id",id).query((rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getObject(5,Long.class),rs.getBoolean(6)})
                .optional().orElseThrow(ApiException::notFound);
        long taskId=(Long)row[0]; String from=(String)row[1]; String to=in.status()==null?"":in.status().toUpperCase();
        boolean assignee=(Long)row[2]==me.staffId(), issuer=(Long)row[3]==me.staffId(), verifier=row[4]!=null&&(Long)row[4]==me.staffId();
        boolean valid = assignee && (from.equals("OPEN")&&to.equals("ACCEPTED") || from.equals("ACCEPTED")&&to.equals("IN_PROGRESS")
                || List.of("IN_PROGRESS","RETURNED").contains(from)&&to.equals("SUBMITTED"))
                || (issuer||verifier) && from.equals("SUBMITTED") && List.of("CLOSED","RETURNED").contains(to)
                || issuer && !List.of("CLOSED","CANCELLED").contains(from) && to.equals("CANCELLED");
        if (!valid) throw ApiException.conflict("ICCP-TSK-003","That task transition is not allowed.");
        int progress = to.equals("CLOSED") ? 100 : in.progressPct()==null ? (Integer)jdbc.sql("SELECT progress_pct FROM tsk.task WHERE id=:id").param("id",taskId).query(Integer.class).single() : in.progressPct();
        jdbc.sql("UPDATE tsk.task SET status=:to,progress_pct=:progress,acknowledged_at=CASE WHEN :to='ACCEPTED' THEN now() ELSE acknowledged_at END,closed_at=CASE WHEN :to='CLOSED' THEN now() ELSE NULL END WHERE id=:id")
                .param("to",to).param("progress",progress).param("id",taskId).update();
        jdbc.sql("INSERT INTO tsk.task_event(task_id,event_type,from_status,to_status,comment,actor_staff_id) VALUES(:id,'STATUS_CHANGED',:from,:to,:comment,:actor)")
                .param("id",taskId).param("from",from).param("to",to).param("comment",in.comment()).param("actor",me.staffId()).update();
        audit.record("TSK","TASK_TRANSITION","task",id,Map.of("from",from,"to",to));
    }

    private long staffId(UUID id) {
        if (id == null) throw ApiException.badRequest("ICCP-TSK-004","Choose an assignee.");
        return jdbc.sql("SELECT id FROM dir.staff_member WHERE public_id=:id AND employment_status IN ('ACTIVE','ON_LEAVE')")
                .param("id",id).query(Long.class).optional().orElseThrow(ApiException::notFound);
    }
    private static String allowed(String value,List<String> allowed,String fallback) {
        String v=value==null?fallback:value.toUpperCase(); if(!allowed.contains(v)) throw ApiException.badRequest("ICCP-TSK-005","Invalid option."); return v;
    }
}
