package tz.go.tirdo.iccp.sec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.go.tirdo.iccp.platform.audit.AuditService;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.sec.api.SystemSettingsApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SystemSettingsService implements SystemSettingsApi {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public SystemSettingsService(JdbcClient jdbc,ObjectMapper mapper,AuditService audit){this.jdbc=jdbc;this.mapper=mapper;this.audit=audit;}

    public record Feature(String code,boolean enabled){}
    public record Setting(String code,String category,String name,String description,JsonNode value,JsonNode defaultValue,String valueType,boolean isPublic){}
    public record Snapshot(List<Feature> features,List<Setting> settings){}

    public Snapshot all(){return new Snapshot(features(),settings(false));}
    public Snapshot publicSnapshot(){return new Snapshot(features(),settings(true));}
    public boolean enabled(String code){return jdbc.sql("SELECT enabled FROM sec.feature_flag WHERE code=:code").param("code",code).query(Boolean.class).optional().orElse(true);}
    public boolean booleanValue(String code,boolean fallback){return jdbc.sql("SELECT (value #>> '{}')::boolean FROM sec.system_setting WHERE code=:code").param("code",code).query(Boolean.class).optional().orElse(fallback);}

    @Transactional
    public void updateFeature(String code,boolean enabled){
        int n=jdbc.sql("UPDATE sec.feature_flag SET enabled=:enabled WHERE code=:code").param("enabled",enabled).param("code",code).update();
        if(n==0)throw ApiException.notFound();
        audit.record("SEC","FEATURE_FLAG_CHANGED","feature_flag",code,Map.of("enabled",enabled));
    }

    @Transactional
    public void updateSetting(String code,JsonNode value){
        String type=jdbc.sql("SELECT value_type FROM sec.system_setting WHERE code=:code").param("code",code).query(String.class).optional().orElseThrow(ApiException::notFound);
        validate(type,value);
        jdbc.sql("UPDATE sec.system_setting SET value=CAST(:value AS jsonb),updated_at=now(),updated_by=coalesce(current_setting('iccp.actor',true),current_user) WHERE code=:code")
                .param("value",value.toString()).param("code",code).update();
        audit.record("SEC","SYSTEM_SETTING_CHANGED","system_setting",code,Map.of("valueType",type));
    }

    private List<Feature> features(){return jdbc.sql("SELECT code,enabled FROM sec.feature_flag ORDER BY code").query(Feature.class).list();}
    private List<Setting> settings(boolean publicOnly){
        String sql="SELECT code,category,name,description,value::text,default_value::text,value_type,public FROM sec.system_setting"+(publicOnly?" WHERE public":"")+" ORDER BY category,name";
        return jdbc.sql(sql).query((rs,n)->new Setting(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),read(rs.getString(5)),read(rs.getString(6)),rs.getString(7),rs.getBoolean(8))).list();
    }
    private JsonNode read(String json){try{return mapper.readTree(json);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void validate(String type,JsonNode value){
        boolean valid=value!=null&&!value.isNull()&&switch(type){case "BOOLEAN"->value.isBoolean();case "INTEGER"->value.isIntegralNumber()&&value.asInt()>=1&&value.asInt()<=1000;case "TEXT"->value.isTextual()&&value.asText().length()<=500;default->false;};
        if(!valid)throw ApiException.badRequest("ICCP-SEC-SET-001","The setting value is invalid.");
    }
}
