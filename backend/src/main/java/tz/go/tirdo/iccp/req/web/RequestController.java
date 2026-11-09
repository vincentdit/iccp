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
    public record RequestRow(UUID publicId,String referenceNo,String typeName,String category,String status,String summary,OffsetDateTime submittedAt,OffsetDateTime updatedAt){}
    public record Submit(long requestTypeId,Map<String,Object> formData){}

    @GetMapping("/types")
    public List<Type> types(){return jdbc.sql("""
        SELECT t.id,t.code,t.name_en,t.name_sw,c.name_en category,t.sla_hours,t.form_schema::text form_schema
          FROM req.request_type t JOIN req.request_category c ON c.id=t.category_id WHERE t.active ORDER BY c.name_en,t.name_en
        """).query(Type.class).list();}

    @GetMapping("/requests")
    public List<RequestRow> requests(){return jdbc.sql("""
        SELECT r.public_id,r.reference_no,t.name_en type_name,c.name_en category,r.status,
               coalesce(r.form_data->>'summary',r.form_data->>'description',r.form_data->>'purpose','') summary,
               r.submitted_at,coalesce(r.updated_at,r.created_at) updated_at
          FROM req.service_request r JOIN req.request_type t ON t.id=r.request_type_id JOIN req.request_category c ON c.id=t.category_id
         WHERE r.requester_staff_id=:me ORDER BY coalesce(r.updated_at,r.created_at) DESC
        """).param("me",CurrentUser.get().staffId()).query(RequestRow.class).list();}

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
}
