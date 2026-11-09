package tz.go.tirdo.iccp.req.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@RequestMapping("/api/v1/req")
public class RequestController {
    private final JdbcClient jdbc; private final ObjectMapper mapper; private final AuditService audit;
    public RequestController(JdbcClient jdbc,ObjectMapper mapper,AuditService audit){this.jdbc=jdbc;this.mapper=mapper;this.audit=audit;}
    public record Type(long id,String code,String nameEn,String nameSw,String category,int slaHours,String formSchema){}
    public record RequestRow(UUID publicId,String referenceNo,String typeName,String category,String status,String summary,OffsetDateTime submittedAt,OffsetDateTime updatedAt,OffsetDateTime slaDueAt,String requesterName,String agentName,boolean canResolve){}
    public record Submit(long requestTypeId,Map<String,Object> formData){}
    public record Action(String status,UUID agentStaffPublicId,String note,Integer satisfaction){}

    @GetMapping("/types")
    public List<Type> types(){return jdbc.sql("""
        SELECT t.id,t.code,t.name_en,t.name_sw,c.name_en category,t.sla_hours,t.form_schema::text form_schema
          FROM req.request_type t JOIN req.request_category c ON c.id=t.category_id WHERE t.active ORDER BY c.name_en,t.name_en
        """).query(Type.class).list();}

    @GetMapping("/requests")
    public List<RequestRow> requests(){return jdbc.sql("""
        SELECT r.public_id,r.reference_no,t.name_en type_name,c.name_en category,r.status,
               coalesce(r.form_data->>'summary',r.form_data->>'description',r.form_data->>'purpose','') summary,
               r.submitted_at,coalesce(r.updated_at,r.created_at) updated_at,
               r.submitted_at + make_interval(hours=>t.sla_hours) sla_due_at,
               concat_ws(' ',nullif(requester.title,''),requester.first_name,requester.last_name) requester_name,
               concat_ws(' ',nullif(agent.title,''),agent.first_name,agent.last_name) agent_name,
               false can_resolve
          FROM req.service_request r JOIN req.request_type t ON t.id=r.request_type_id JOIN req.request_category c ON c.id=t.category_id
          JOIN dir.staff_member requester ON requester.id=r.requester_staff_id LEFT JOIN dir.staff_member agent ON agent.id=r.assigned_agent_staff_id
         WHERE r.requester_staff_id=:me ORDER BY coalesce(r.updated_at,r.created_at) DESC
        """).param("me",CurrentUser.get().staffId()).query(RequestRow.class).list();}

    @GetMapping("/queue")
    public List<RequestRow> queue(){
        CurrentUser.require("SERVICE_AGENT","DIRECTOR","EXECUTIVE");
        var me=CurrentUser.get();
        return jdbc.sql("""
          SELECT r.public_id,r.reference_no,t.name_en type_name,c.name_en category,r.status,
                 coalesce(r.form_data->>'summary',r.form_data->>'description',r.form_data->>'purpose','') summary,
                 r.submitted_at,coalesce(r.updated_at,r.created_at) updated_at,
                 r.submitted_at+make_interval(hours=>t.sla_hours) sla_due_at,
                 concat_ws(' ',nullif(s.title,''),s.first_name,s.last_name) requester_name,
                 concat_ws(' ',nullif(a.title,''),a.first_name,a.last_name) agent_name,
                 (r.status='IN_FULFILMENT' AND (r.assigned_agent_staff_id=:me OR :manager)) can_resolve
            FROM req.service_request r JOIN req.request_type t ON t.id=r.request_type_id JOIN req.request_category c ON c.id=t.category_id
            JOIN dir.staff_member s ON s.id=r.requester_staff_id LEFT JOIN dir.staff_member a ON a.id=r.assigned_agent_staff_id
           WHERE r.status NOT IN ('DRAFT','CLOSED','CANCELLED') AND (t.fulfilment_unit_id IS NULL OR t.fulfilment_unit_id=:unit OR r.assigned_agent_staff_id=:me)
           ORDER BY CASE WHEN r.submitted_at+make_interval(hours=>t.sla_hours)<now() THEN 0 ELSE 1 END,r.submitted_at
        """).param("unit",me.orgUnitId()).param("me",me.staffId())
                .param("manager",me.hasAnyRole("DIRECTOR","EXECUTIVE")).query(RequestRow.class).list();
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UUID submit(@RequestBody Submit in){
        Map<String,Object> data=in.formData()==null?Map.of():in.formData();
        if(data.isEmpty())throw ApiException.badRequest("ICCP-REQ-001","Complete the request details.");
        boolean exists=jdbc.sql("SELECT EXISTS(SELECT 1 FROM req.request_type WHERE id=:id AND active)").param("id",in.requestTypeId()).query(Boolean.class).single();
        if(!exists)throw ApiException.notFound();
        String json;try{json=mapper.writeValueAsString(data);}catch(JsonProcessingException e){throw ApiException.badRequest("ICCP-REQ-002","Invalid request details.");}
        String ref="REQ-"+java.time.Year.now().getValue()+"-"+String.format("%06d",jdbc.sql("SELECT nextval('core.reference_seq')").query(Long.class).single());
        UUID id=jdbc.sql("""
            INSERT INTO req.service_request(reference_no,request_type_id,requester_staff_id,form_data,status,submitted_at)
            VALUES(:ref,:type,:me,CAST(:data AS jsonb),'SUBMITTED',now()) RETURNING public_id
            """).param("ref",ref).param("type",in.requestTypeId()).param("me",CurrentUser.get().staffId()).param("data",json).query(UUID.class).single();
        audit.record("REQ","REQUEST_SUBMITTED","service_request",id,Map.of("referenceNo",ref));
        return id;
    }

    @PostMapping("/requests/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void cancel(@PathVariable UUID id){
        int n=jdbc.sql("UPDATE req.service_request SET status='CANCELLED' WHERE public_id=:id AND requester_staff_id=:me AND status IN ('DRAFT','SUBMITTED','RETURNED')")
                .param("id",id).param("me",CurrentUser.get().staffId()).update();
        if(n==0)throw ApiException.conflict("ICCP-REQ-003","This request can no longer be cancelled.");
        audit.record("REQ","REQUEST_CANCELLED","service_request",id,Map.of());
    }

    @PostMapping("/requests/{id}/action")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void action(@PathVariable UUID id,@RequestBody Action in){
        var me=CurrentUser.get(); String next=in.status()==null?"":in.status().toUpperCase();
        Object[] row=jdbc.sql("SELECT id,status,requester_staff_id,assigned_agent_staff_id FROM req.service_request WHERE public_id=:id")
                .param("id",id).query((rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getObject(4,Long.class)}).optional().orElseThrow(ApiException::notFound);
        String from=(String)row[1]; boolean requester=(Long)row[2]==me.staffId();
        boolean agent=me.hasAnyRole("SERVICE_AGENT","DIRECTOR","EXECUTIVE");
        boolean valid=agent && ((from.equals("SUBMITTED")&&next.equals("IN_FULFILMENT"))||(from.equals("IN_FULFILMENT")&&next.equals("RESOLVED")))
                || requester&&from.equals("RESOLVED")&&next.equals("CLOSED");
        if(!valid)throw ApiException.conflict("ICCP-REQ-004","That request transition is not allowed.");
        Long assigned=(Long)row[3];
        if(next.equals("IN_FULFILMENT")) assigned=in.agentStaffPublicId()==null?me.staffId():staffId(in.agentStaffPublicId());
        if(assigned!=null&&assigned!=me.staffId()&&next.equals("RESOLVED")&&!me.hasAnyRole("DIRECTOR","EXECUTIVE"))throw ApiException.forbidden();
        Integer rating=next.equals("CLOSED")?in.satisfaction():null;
        if(rating!=null&&(rating<1||rating>5))throw ApiException.badRequest("ICCP-REQ-005","Satisfaction must be between 1 and 5.");
        jdbc.sql("UPDATE req.service_request SET status=:next,assigned_agent_staff_id=coalesce(:agent,assigned_agent_staff_id),resolution_note=CASE WHEN :next='RESOLVED' THEN :note ELSE resolution_note END,resolved_at=CASE WHEN :next='RESOLVED' THEN now() ELSE resolved_at END,closed_at=CASE WHEN :next='CLOSED' THEN now() ELSE closed_at END,satisfaction=coalesce(:rating,satisfaction) WHERE id=:id")
                .param("next",next).param("agent",assigned).param("note",in.note()).param("rating",rating).param("id",row[0]).update();
        audit.record("REQ","REQUEST_"+next,"service_request",id,Map.of("from",from));
    }

    private long staffId(UUID id){return jdbc.sql("SELECT id FROM dir.staff_member WHERE public_id=:id AND employment_status='ACTIVE'").param("id",id).query(Long.class).optional().orElseThrow(ApiException::notFound);}
}
