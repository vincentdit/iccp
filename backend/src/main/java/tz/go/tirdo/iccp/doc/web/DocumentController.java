package tz.go.tirdo.iccp.doc.web;

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
@RequestMapping("/api/v1/doc")
public class DocumentController {
    private final JdbcClient jdbc; private final AuditService audit;
    public DocumentController(JdbcClient jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}
    public record Type(String code,String nameEn,String nameSw){}
    public record DocumentRow(UUID publicId,String title,String typeName,String classification,String language,String status,String ownerUnit,LocalDate reviewDueOn,OffsetDateTime updatedAt){}
    public record CreateDocument(String title,String typeCode,String classification,String language,List<String> keywords,LocalDate reviewDueOn){}
    public record State(String status){}

    @GetMapping("/types")
    public List<Type> types(){return jdbc.sql("SELECT code,name_en,name_sw FROM doc.document_type ORDER BY name_en").query(Type.class).list();}

    @GetMapping("/documents")
    public List<DocumentRow> documents(@RequestParam(required=false)String q){
        String term=q==null?"":q.trim();
        return jdbc.sql("""
            SELECT d.public_id,d.title,t.name_en type_name,d.classification,d.language,d.status,u.name_en owner_unit,
                   d.review_due_on,coalesce(d.updated_at,d.created_at) updated_at
              FROM doc.document d JOIN doc.document_type t ON t.id=d.document_type_id JOIN dir.org_unit u ON u.id=d.owner_unit_id
             WHERE d.status<>'DISPOSED' AND (:q='' OR lower(d.title) LIKE lower(:like) OR :q=ANY(d.keywords))
               AND (d.classification IN ('PUBLIC','INTERNAL') OR d.owner_unit_id=:unit OR :records)
             ORDER BY coalesce(d.updated_at,d.created_at) DESC
            """).param("q",term).param("like","%"+term+"%").param("unit",CurrentUser.get().orgUnitId())
                .param("records",CurrentUser.get().hasAnyRole("RECORDS_OFFICER","EXECUTIVE")).query(DocumentRow.class).list();
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UUID create(@RequestBody CreateDocument in){
        if(in.title()==null||in.title().isBlank())throw ApiException.badRequest("ICCP-DOC-001","Document title is required.");
        String classification=value(in.classification(),List.of("PUBLIC","INTERNAL","RESTRICTED","CONFIDENTIAL"),"INTERNAL");
        String language=value(in.language(),List.of("en","sw"),"en");
        long type=jdbc.sql("SELECT id FROM doc.document_type WHERE code=:code").param("code",in.typeCode()).query(Long.class).optional().orElseThrow(ApiException::notFound);
        String[] keywords=(in.keywords()==null?List.<String>of():in.keywords()).stream().filter(s->s!=null&&!s.isBlank()).map(String::trim).toArray(String[]::new);
        UUID id=jdbc.sql("""
            INSERT INTO doc.document(document_type_id,title,owner_unit_id,classification,language,keywords,review_due_on)
            VALUES(:type,:title,:unit,:class,:lang,:keywords,:review) RETURNING public_id
            """).param("type",type).param("title",in.title().trim()).param("unit",CurrentUser.get().orgUnitId())
                .param("class",classification).param("lang",language).param("keywords",keywords).param("review",in.reviewDueOn()).query(UUID.class).single();
        audit.record("DOC","DOCUMENT_CREATED","document",id,Map.of("classification",classification));
        return id;
    }

    @PostMapping("/documents/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void state(@PathVariable UUID id,@RequestBody State in){
        CurrentUser.require("RECORDS_OFFICER","DIRECTOR","EXECUTIVE");
        String next=value(in.status(),List.of("IN_REVIEW","PUBLISHED","SUPERSEDED"),"");
        Object[] row=jdbc.sql("SELECT status,title FROM doc.document WHERE public_id=:id").param("id",id).query((rs,n)->new Object[]{rs.getString(1),rs.getString(2)}).optional().orElseThrow(ApiException::notFound);
        String from=(String)row[0];
        boolean valid=from.equals("DRAFT")&&next.equals("IN_REVIEW")||from.equals("IN_REVIEW")&&next.equals("PUBLISHED")||from.equals("PUBLISHED")&&next.equals("SUPERSEDED");
        if(!valid)throw ApiException.conflict("ICCP-DOC-002","That document transition is not allowed.");
        jdbc.sql("UPDATE doc.document SET status=:next WHERE public_id=:id").param("next",next).param("id",id).update();
        audit.record("DOC","DOCUMENT_STATUS_CHANGED","document",id,Map.of("from",from,"to",next));
    }
    private static String value(String in,List<String> choices,String fallback){String v=in==null?fallback:in.toUpperCase();if(!choices.contains(v))throw ApiException.badRequest("ICCP-DOC-003","Invalid option.");return v;}
}
