package tz.go.tirdo.iccp.msg.web;

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
@RequestMapping("/api/v1/msg")
public class MessagingController {
    private final JdbcClient jdbc;
    private final AuditService audit;
    public MessagingController(JdbcClient jdbc, AuditService audit){this.jdbc=jdbc;this.audit=audit;}

    public record Conversation(UUID publicId,String type,String title,String ownerName,OffsetDateTime updatedAt,int memberCount,String lastMessage,int unreadCount,boolean muted){}
    public record Message(long id,String senderName,String body,OffsetDateTime createdAt,OffsetDateTime editedAt,boolean mine){}
    public record CreateConversation(String title,List<UUID> memberPublicIds){}
    public record SendMessage(String body){}
    public record MemberChange(UUID staffPublicId,String role){}
    public record Moderation(String reason){}

    @GetMapping("/conversations")
    public List<Conversation> conversations(){
        long me=CurrentUser.get().staffId();
        return jdbc.sql("""
            SELECT c.public_id,c.conv_type type,coalesce(c.title,'Conversation') title,
                   concat_ws(' ',nullif(o.title,''),o.first_name,o.last_name) owner_name,
                   coalesce(c.updated_at,c.created_at) updated_at,
                   (SELECT count(*)::int FROM msg.conversation_member x WHERE x.conversation_id=c.id AND x.left_at IS NULL) member_count,
                   coalesce((SELECT CASE WHEN m.deleted_at IS NULL AND m.hidden_at IS NULL THEN left(m.body,100) ELSE 'Message unavailable' END
                     FROM msg.message m WHERE m.conversation_id=c.id ORDER BY m.id DESC LIMIT 1),'') last_message,
                   (SELECT count(*)::int FROM msg.message m WHERE m.conversation_id=c.id AND m.id>coalesce(mine.last_read_message_id,0)) unread_count,
                   mine.muted
              FROM msg.conversation c JOIN msg.conversation_member mine ON mine.conversation_id=c.id AND mine.staff_id=:me AND mine.left_at IS NULL
              LEFT JOIN dir.staff_member o ON o.id=c.owner_staff_id ORDER BY coalesce(c.updated_at,c.created_at) DESC
            """).param("me",me).query(Conversation.class).list();
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UUID create(@RequestBody CreateConversation in){
        long me=CurrentUser.get().staffId();
        if(in.title()==null||in.title().isBlank())throw ApiException.badRequest("ICCP-MSG-001","Conversation title is required.");
        UUID id=jdbc.sql("INSERT INTO msg.conversation(conv_type,title,owner_staff_id) VALUES('GROUP',:title,:me) RETURNING public_id")
                .param("title",in.title().trim()).param("me",me).query(UUID.class).single();
        long conv=jdbc.sql("SELECT id FROM msg.conversation WHERE public_id=:id").param("id",id).query(Long.class).single();
        jdbc.sql("INSERT INTO msg.conversation_member(conversation_id,staff_id,member_role) VALUES(:c,:s,'OWNER')")
                .param("c",conv).param("s",me).update();
        for(UUID member:in.memberPublicIds()==null?List.<UUID>of():in.memberPublicIds()){
            Long staff=jdbc.sql("SELECT id FROM dir.staff_member WHERE public_id=:id AND employment_status='ACTIVE'").param("id",member).query(Long.class).optional().orElse(null);
            if(staff!=null&&staff!=me)jdbc.sql("INSERT INTO msg.conversation_member(conversation_id,staff_id) VALUES(:c,:s) ON CONFLICT DO NOTHING").param("c",conv).param("s",staff).update();
        }
        audit.record("MSG","CONVERSATION_CREATED","conversation",id,Map.of("title",in.title().trim()));
        return id;
    }

    @GetMapping("/conversations/{id}/messages")
    public List<Message> messages(@PathVariable UUID id){
        long me=CurrentUser.get().staffId(); long conv=conversation(id,me);
        return jdbc.sql("""
            SELECT m.id,concat_ws(' ',nullif(s.title,''),s.first_name,s.last_name) sender_name,
                   CASE WHEN m.deleted_at IS NOT NULL THEN 'Message removed' WHEN m.hidden_at IS NOT NULL THEN 'Message hidden by moderator' ELSE m.body END body,
                   m.created_at,m.edited_at,(m.sender_staff_id=:me) mine
              FROM msg.message m JOIN dir.staff_member s ON s.id=m.sender_staff_id
             WHERE m.conversation_id=:c ORDER BY m.id
            """).param("me",me).param("c",conv).query(Message.class).list();
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public long send(@PathVariable UUID id,@RequestBody SendMessage in){
        long me=CurrentUser.get().staffId(); long conv=conversation(id,me);
        if(in.body()==null||in.body().isBlank()||in.body().length()>10000)throw ApiException.badRequest("ICCP-MSG-002","Message must contain 1 to 10,000 characters.");
        long message=jdbc.sql("INSERT INTO msg.message(conversation_id,sender_staff_id,body) VALUES(:c,:me,:body) RETURNING id")
                .param("c",conv).param("me",me).param("body",in.body().trim()).query(Long.class).single();
        jdbc.sql("UPDATE msg.conversation SET updated_at=now() WHERE id=:c").param("c",conv).update();
        return message;
    }

    @PostMapping("/conversations/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id){
        long me=CurrentUser.get().staffId(),conv=conversation(id,me);
        jdbc.sql("UPDATE msg.conversation_member SET last_read_message_id=(SELECT max(id) FROM msg.message WHERE conversation_id=:c) WHERE conversation_id=:c AND staff_id=:me")
                .param("c",conv).param("me",me).update();
    }

    @PostMapping("/conversations/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public void addMember(@PathVariable UUID id,@RequestBody MemberChange in){
        long me=CurrentUser.get().staffId(),conv=managedConversation(id,me);
        if(in.staffPublicId()==null)throw ApiException.badRequest("ICCP-MSG-003","Choose a staff member.");
        long staff=jdbc.sql("SELECT id FROM dir.staff_member WHERE public_id=:id AND employment_status='ACTIVE'").param("id",in.staffPublicId()).query(Long.class).optional().orElseThrow(ApiException::notFound);
        String role=in.role()==null?"MEMBER":in.role().toUpperCase();if(!List.of("ADMIN","MEMBER").contains(role))throw ApiException.badRequest("ICCP-MSG-004","Invalid member role.");
        jdbc.sql("INSERT INTO msg.conversation_member(conversation_id,staff_id,member_role) VALUES(:c,:s,:role) ON CONFLICT(conversation_id,staff_id) DO UPDATE SET member_role=:role,left_at=NULL")
                .param("c",conv).param("s",staff).param("role",role).update();
        audit.record("MSG","MEMBER_ADDED","conversation",id,Map.of("staffPublicId",in.staffPublicId().toString()));
    }

    @PatchMapping("/conversations/{id}/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void edit(@PathVariable UUID id,@PathVariable long messageId,@RequestBody SendMessage in){
        long me=CurrentUser.get().staffId(),conv=conversation(id,me);
        if(in.body()==null||in.body().isBlank()||in.body().length()>10000)throw ApiException.badRequest("ICCP-MSG-002","Message must contain 1 to 10,000 characters.");
        String old=jdbc.sql("SELECT body FROM msg.message WHERE id=:message AND conversation_id=:c AND sender_staff_id=:me AND deleted_at IS NULL AND hidden_at IS NULL AND created_at>now()-interval '15 minutes'")
                .param("message",messageId).param("c",conv).param("me",me).query(String.class).optional().orElseThrow(ApiException::notFound);
        jdbc.sql("INSERT INTO msg.message_revision(message_id,body,revision_type) VALUES(:id,:body,'EDIT')").param("id",messageId).param("body",old).update();
        jdbc.sql("UPDATE msg.message SET body=:body,edited_at=now() WHERE id=:id").param("body",in.body().trim()).param("id",messageId).update();
    }

    @DeleteMapping("/conversations/{id}/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id,@PathVariable long messageId){
        long me=CurrentUser.get().staffId(),conv=conversation(id,me);
        String old=jdbc.sql("SELECT body FROM msg.message WHERE id=:message AND conversation_id=:c AND sender_staff_id=:me AND deleted_at IS NULL")
                .param("message",messageId).param("c",conv).param("me",me).query(String.class).optional().orElseThrow(ApiException::notFound);
        jdbc.sql("INSERT INTO msg.message_revision(message_id,body,revision_type) VALUES(:id,:body,'DELETE')").param("id",messageId).param("body",old).update();
        jdbc.sql("UPDATE msg.message SET body='[removed]',deleted_at=now() WHERE id=:id").param("id",messageId).update();
    }

    @PostMapping("/conversations/{id}/messages/{messageId}/report")
    @ResponseStatus(HttpStatus.CREATED)
    public void report(@PathVariable UUID id,@PathVariable long messageId,@RequestBody Moderation in){
        long me=CurrentUser.get().staffId(),conv=conversation(id,me);
        if(in.reason()==null||in.reason().isBlank())throw ApiException.badRequest("ICCP-MSG-005","A report reason is required.");
        int n=jdbc.sql("INSERT INTO msg.message_report(message_id,reported_by_staff_id,reason) SELECT id,:me,:reason FROM msg.message WHERE id=:message AND conversation_id=:c")
                .param("me",me).param("reason",in.reason().trim()).param("message",messageId).param("c",conv).update();
        if(n==0)throw ApiException.notFound();
        audit.record("MSG","MESSAGE_REPORTED","conversation",id,Map.of("messageId",messageId));
    }

    private long managedConversation(UUID id,long me){return jdbc.sql("SELECT c.id FROM msg.conversation c JOIN msg.conversation_member m ON m.conversation_id=c.id WHERE c.public_id=:id AND m.staff_id=:me AND m.left_at IS NULL AND m.member_role IN ('OWNER','ADMIN')").param("id",id).param("me",me).query(Long.class).optional().orElseThrow(ApiException::notFound);}
    private long conversation(UUID id,long me){return jdbc.sql("SELECT c.id FROM msg.conversation c JOIN msg.conversation_member m ON m.conversation_id=c.id WHERE c.public_id=:id AND m.staff_id=:me AND m.left_at IS NULL").param("id",id).param("me",me).query(Long.class).optional().orElseThrow(ApiException::notFound);}
}
