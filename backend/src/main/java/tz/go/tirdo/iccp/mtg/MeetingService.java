package tz.go.tirdo.iccp.mtg;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.dir.api.DirectoryApi;
import tz.go.tirdo.iccp.dir.api.StaffRef;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.security.IccpPrincipal;
import tz.go.tirdo.iccp.platform.security.Roles;
import tz.go.tirdo.iccp.platform.web.ApiException;

import java.sql.Timestamp;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MeetingService {
    private final JdbcClient jdbc;
    private final DirectoryApi directory;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public MeetingService(JdbcClient jdbc, DirectoryApi directory, AuditService audit, ObjectMapper mapper) {
        this.jdbc = jdbc; this.directory = directory; this.audit = audit; this.mapper = mapper;
    }

    public List<MeetingTypes.Type> types() {
        return jdbc.sql("SELECT code, name_en, name_sw, confidentiality, quorum_percent FROM mtg.meeting_type ORDER BY name_en")
                .query(MeetingTypes.Type.class).list();
    }

    public List<MeetingTypes.Summary> list() {
        return jdbc.sql(summarySql() + " WHERE EXISTS (SELECT 1 FROM mtg.meeting_participant mine WHERE mine.meeting_id=m.id AND mine.staff_id=:me) ORDER BY m.starts_at")
                .param("me", CurrentUser.get().staffId()).query(MeetingTypes.Summary.class).list();
    }

    public MeetingTypes.Detail detail(UUID publicId) {
        long me = CurrentUser.get().staffId();
        MeetingTypes.Summary m = jdbc.sql(summarySql() + " WHERE m.public_id=:id AND EXISTS (SELECT 1 FROM mtg.meeting_participant mine WHERE mine.meeting_id=m.id AND mine.staff_id=:me)")
                .param("id", publicId).param("me", me).query(MeetingTypes.Summary.class).optional().orElseThrow(ApiException::notFound);
        List<MeetingTypes.Participant> people = jdbc.sql("""
                SELECT s.public_id, concat_ws(' ', nullif(s.title,''), s.first_name, s.last_name) display_name,
                       p.title_en position_title, u.name_en unit_name, mp.participant_role role, mp.rsvp, mp.attendance
                  FROM mtg.meeting_participant mp JOIN mtg.meeting m ON m.id=mp.meeting_id
                  JOIN dir.staff_member s ON s.id=mp.staff_id LEFT JOIN dir.position p ON p.id=s.position_id
                  JOIN dir.org_unit u ON u.id=s.org_unit_id WHERE m.public_id=:id
                 ORDER BY CASE mp.participant_role WHEN 'CHAIR' THEN 1 WHEN 'SECRETARY' THEN 2 ELSE 3 END, display_name
                """).param("id", publicId).query(MeetingTypes.Participant.class).list();
        List<MeetingTypes.Agenda> agenda = jdbc.sql("""
                SELECT a.item_no, a.title, a.minutes_allocated FROM mtg.agenda_item a
                JOIN mtg.meeting m ON m.id=a.meeting_id WHERE m.public_id=:id ORDER BY a.item_no
                """).param("id", publicId).query(MeetingTypes.Agenda.class).list();
        return new MeetingTypes.Detail(m, people, agenda);
    }

    @Transactional
    public UUID create(MeetingTypes.Create in) {
        IccpPrincipal actor = CurrentUser.get();
        CurrentUser.require(Roles.MEETING_SECRETARY, Roles.MEETING_CHAIR, Roles.DIRECTOR, Roles.EXECUTIVE);
        if (!in.endsAt().isAfter(in.startsAt())) throw ApiException.badRequest("ICCP-MTG-001", "Meeting end time must be after its start time.");
        String mode = in.mode().toUpperCase();
        if (!List.of("PHYSICAL", "ONLINE", "HYBRID").contains(mode)) throw ApiException.badRequest("ICCP-MTG-002", "Select a valid meeting mode.");
        if (!mode.equals("PHYSICAL") && (in.onlineLink()==null || in.onlineLink().isBlank())) throw ApiException.badRequest("ICCP-MTG-003", "An online link is required.");
        StaffRef chair = staff(in.chairStaffPublicId()); StaffRef secretary = staff(in.secretaryStaffPublicId());
        var type = jdbc.sql("SELECT id, confidentiality FROM mtg.meeting_type WHERE code=:code").param("code", in.typeCode()).query((rs,n) -> new Object[]{rs.getLong(1),rs.getString(2)}).optional().orElseThrow(ApiException::notFound);
        UUID publicId = UUID.randomUUID();
        long id = jdbc.sql("""
                INSERT INTO mtg.meeting(public_id,meeting_type_id,title,starts_at,ends_at,mode,venue,online_link,chair_staff_id,secretary_staff_id,confidentiality,status)
                VALUES(:publicId,:typeId,:title,:starts,:ends,:mode,:venue,:link,:chair,:secretary,:confidentiality,'INVITED') RETURNING id
                """).param("publicId",publicId).param("typeId",type[0]).param("title",in.title().trim())
                .param("starts", Timestamp.from(in.startsAt().toInstant())).param("ends", Timestamp.from(in.endsAt().toInstant()))
                .param("mode",mode).param("venue",blank(in.venue())).param("link",blank(in.onlineLink()))
                .param("chair",chair.id()).param("secretary",secretary.id()).param("confidentiality",type[1]).query(Long.class).single();
        Set<Long> ids = new LinkedHashSet<>(); ids.add(chair.id()); ids.add(secretary.id());
        for (UUID pid : in.participantStaffPublicIds()==null?List.<UUID>of():in.participantStaffPublicIds()) ids.add(staff(pid).id());
        for (long sid : ids) {
            String role = sid==chair.id()?"CHAIR":sid==secretary.id()?"SECRETARY":"MEMBER";
            jdbc.sql("INSERT INTO mtg.meeting_participant(meeting_id,staff_id,participant_role,rsvp) VALUES(:m,:s,:r,:rsvp)")
                    .param("m",id).param("s",sid).param("r",role).param("rsvp",sid==actor.staffId()?"ACCEPTED":null).update();
        }
        short no=1; for (String item : in.agenda()==null?List.<String>of():in.agenda()) if (!item.isBlank()) {
            jdbc.sql("INSERT INTO mtg.agenda_item(meeting_id,item_no,title) VALUES(:m,:n,:t)").param("m",id).param("n",no++).param("t",item.trim()).update();
        }
        audit.record("MTG","MEETING_CREATED","meeting",publicId, Map.of("participants",ids.size(),"mode",mode));
        return publicId;
    }

    @Transactional
    public void rsvp(UUID publicId, String response) {
        String value=response.toUpperCase(); if (!List.of("ACCEPTED","DECLINED").contains(value)) throw ApiException.badRequest("ICCP-MTG-004","Select accept or decline.");
        int n=jdbc.sql("UPDATE mtg.meeting_participant mp SET rsvp=:r FROM mtg.meeting m WHERE mp.meeting_id=m.id AND m.public_id=:id AND mp.staff_id=:me")
                .param("r",value).param("id",publicId).param("me",CurrentUser.get().staffId()).update();
        if(n==0) throw ApiException.notFound();
        audit.record("MTG","RSVP_UPDATED","meeting",publicId,Map.of("response",value));
    }

    @Transactional public void attendance(UUID publicId, MeetingTypes.Attendance in) {
        long meetingId=managedMeeting(publicId,false); String value=in.status().toUpperCase();
        if(!List.of("PRESENT","APOLOGY","ABSENT","IN_ATTENDANCE").contains(value)) throw ApiException.badRequest("ICCP-MTG-005","Select a valid attendance status.");
        int n=jdbc.sql("UPDATE mtg.meeting_participant mp SET attendance=:v FROM dir.staff_member s WHERE mp.staff_id=s.id AND mp.meeting_id=:m AND s.public_id=:staff")
                .param("v",value).param("m",meetingId).param("staff",in.staffPublicId()).update(); if(n==0) throw ApiException.notFound();
        jdbc.sql("UPDATE mtg.meeting SET status='HELD' WHERE id=:m AND status IN ('PLANNED','INVITED')").param("m",meetingId).update();
        audit.record("MTG","ATTENDANCE_UPDATED","meeting",publicId,Map.of("status",value));
    }

    public MeetingTypes.Minutes minutes(UUID publicId) {
        IccpPrincipal p=CurrentUser.get(); long meetingId=visibleMeeting(publicId);
        Object[] row=jdbc.sql("SELECT version_no,status,content->>'summary' FROM mtg.minutes_version WHERE meeting_id=:m ORDER BY version_no DESC LIMIT 1")
                .param("m",meetingId).query((rs,n)->new Object[]{rs.getInt(1),rs.getString(2),rs.getString(3)}).optional().orElse(null);
        List<MeetingTypes.Resolution> rs=row==null?List.of():jdbc.sql("SELECT r.public_id,r.resolution_no,r.text,s.public_id owner_staff_public_id,concat_ws(' ',nullif(s.title,''),s.first_name,s.last_name) owner_name,r.due_on,r.status FROM mtg.resolution r LEFT JOIN dir.staff_member s ON s.id=r.owner_staff_id WHERE r.meeting_id=:m ORDER BY r.resolution_no")
                .param("m",meetingId).query(MeetingTypes.Resolution.class).list();
        String role=meetingRole(meetingId,p.staffId()), status=row==null?null:(String)row[1];
        boolean edit=("SECRETARY".equals(role)||p.hasAnyRole(Roles.MEETING_SECRETARY,Roles.EXECUTIVE))&&(status==null||"DRAFT".equals(status));
        return new MeetingTypes.Minutes(row==null?null:(Integer)row[0],status,row==null?"":(String)row[2],rs,edit,edit&&row!=null,"CHAIR".equals(role)&&"CHAIR_APPROVAL".equals(status));
    }

    @Transactional public void saveMinutes(UUID publicId, MeetingTypes.MinutesInput in) {
        long m=managedMeeting(publicId,true); Long v=jdbc.sql("SELECT id FROM mtg.minutes_version WHERE meeting_id=:m AND status='DRAFT' ORDER BY version_no DESC LIMIT 1").param("m",m).query(Long.class).optional().orElse(null);
        String json; try{json=mapper.writeValueAsString(Map.of("summary",in.summary().trim()));}catch(JsonProcessingException e){throw new IllegalStateException(e);}
        if(v==null) v=jdbc.sql("INSERT INTO mtg.minutes_version(meeting_id,version_no,content) VALUES(:m,coalesce((SELECT max(version_no)+1 FROM mtg.minutes_version WHERE meeting_id=:m),1),CAST(:c AS jsonb)) RETURNING id").param("m",m).param("c",json).query(Long.class).single();
        else jdbc.sql("UPDATE mtg.minutes_version SET content=CAST(:c AS jsonb) WHERE id=:v").param("c",json).param("v",v).update();
        jdbc.sql("DELETE FROM mtg.resolution WHERE meeting_id=:m AND minutes_version_id=:v").param("m",m).param("v",v).update(); int i=1;
        for(var r:in.resolutions()==null?List.<MeetingTypes.ResolutionInput>of():in.resolutions()){
            Long owner=r.ownerStaffPublicId()==null?null:staff(r.ownerStaffPublicId()).id(); String no=jdbc.sql("SELECT resolution_prefix||'-'||extract(year from starts_at)::int||'-'||lpad(CAST(:n AS text),3,'0') FROM mtg.meeting x JOIN mtg.meeting_type t ON t.id=x.meeting_type_id WHERE x.id=:m").param("n",i++).param("m",m).query(String.class).single();
            jdbc.sql("INSERT INTO mtg.resolution(meeting_id,minutes_version_id,resolution_no,text,owner_staff_id,due_on) VALUES(:m,:v,:no,:text,:owner,:due)").param("m",m).param("v",v).param("no",no).param("text",r.text().trim()).param("owner",owner).param("due",r.dueOn()).update();
        }
        jdbc.sql("UPDATE mtg.meeting SET status='MINUTES_DRAFT' WHERE id=:m").param("m",m).update(); audit.record("MTG","MINUTES_SAVED","meeting",publicId,Map.of("resolutions",i-1));
    }
    @Transactional public void submitMinutes(UUID id){long m=managedMeeting(id,true);int n=jdbc.sql("UPDATE mtg.minutes_version SET status='CHAIR_APPROVAL' WHERE meeting_id=:m AND status='DRAFT'").param("m",m).update();if(n==0)throw ApiException.conflict("ICCP-MTG-006","Save draft minutes first.");audit.record("MTG","MINUTES_SUBMITTED","meeting",id,Map.of());}
    @Transactional public void approveMinutes(UUID id){IccpPrincipal p=CurrentUser.get();long m=managedMeeting(id,false);if(!"CHAIR".equals(meetingRole(m,p.staffId()))&&!p.hasAnyRole(Roles.MEETING_CHAIR,Roles.EXECUTIVE))throw ApiException.forbidden();int n=jdbc.sql("UPDATE mtg.minutes_version SET status='APPROVED',approved_by_staff_id=:me,approved_at=now() WHERE meeting_id=:m AND status='CHAIR_APPROVAL'").param("me",p.staffId()).param("m",m).update();if(n==0)throw ApiException.conflict("ICCP-MTG-007","Minutes are not ready for approval.");jdbc.sql("UPDATE mtg.meeting SET status='MINUTES_APPROVED' WHERE id=:m").param("m",m).update();audit.record("MTG","MINUTES_APPROVED","meeting",id,Map.of());}
    private long visibleMeeting(UUID id){return jdbc.sql("SELECT m.id FROM mtg.meeting m JOIN mtg.meeting_participant p ON p.meeting_id=m.id WHERE m.public_id=:id AND p.staff_id=:me").param("id",id).param("me",CurrentUser.get().staffId()).query(Long.class).optional().orElseThrow(ApiException::notFound);}
    private long managedMeeting(UUID id,boolean secretary){long m=visibleMeeting(id);IccpPrincipal p=CurrentUser.get();String r=meetingRole(m,p.staffId());boolean ok=secretary?("SECRETARY".equals(r)||p.hasAnyRole(Roles.MEETING_SECRETARY,Roles.EXECUTIVE)):(List.of("CHAIR","SECRETARY").contains(r)||p.hasAnyRole(Roles.MEETING_SECRETARY,Roles.MEETING_CHAIR,Roles.EXECUTIVE));if(!ok)throw ApiException.forbidden();return m;}
    private String meetingRole(long m,long s){return jdbc.sql("SELECT participant_role FROM mtg.meeting_participant WHERE meeting_id=:m AND staff_id=:s").param("m",m).param("s",s).query(String.class).optional().orElse("");}

    private StaffRef staff(UUID id){ return directory.findByPublicId(id).filter(s -> List.of("ACTIVE","ON_LEAVE").contains(s.employmentStatus())).orElseThrow(ApiException::notFound); }
    private static String blank(String s){ return s==null||s.isBlank()?null:s.trim(); }
    private static String summarySql(){ return """
            SELECT m.public_id,m.title,t.code type_code,t.name_en type_name_en,t.name_sw type_name_sw,m.starts_at,m.ends_at,m.mode,m.venue,m.online_link,m.status,m.confidentiality,
                   concat_ws(' ',nullif(cs.title,''),cs.first_name,cs.last_name) chair_name,
                   concat_ws(' ',nullif(ss.title,''),ss.first_name,ss.last_name) secretary_name,
                   (SELECT count(*)::int FROM mtg.meeting_participant x WHERE x.meeting_id=m.id) participant_count,
                   mine.participant_role my_role,mine.rsvp my_rsvp
              FROM mtg.meeting m JOIN mtg.meeting_type t ON t.id=m.meeting_type_id
              JOIN dir.staff_member cs ON cs.id=m.chair_staff_id JOIN dir.staff_member ss ON ss.id=m.secretary_staff_id
              JOIN mtg.meeting_participant mine ON mine.meeting_id=m.id AND mine.staff_id=:me
            """; }
}
