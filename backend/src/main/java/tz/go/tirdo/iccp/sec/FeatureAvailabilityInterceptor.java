package tz.go.tirdo.iccp.sec;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.sec.api.SystemSettingsApi;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FeatureAvailabilityInterceptor implements HandlerInterceptor {
    private final SystemSettingsApi settings;
    private static final Map<String,String> MODULES=new LinkedHashMap<>();
    static {MODULES.put("/api/v1/com/","MODULE_ANNOUNCEMENTS");MODULES.put("/api/v1/fin/","MODULE_FINANCE");MODULES.put("/api/v1/dir/imports","MODULE_HR_ADMIN");MODULES.put("/api/v1/dir/exceptions","MODULE_HR_ADMIN");MODULES.put("/api/v1/dir/acting","MODULE_HR_ADMIN");MODULES.put("/api/v1/dir/","MODULE_DIRECTORY");MODULES.put("/api/v1/ntf/","MODULE_NOTIFICATIONS");MODULES.put("/api/v1/mtg/","MODULE_MEETINGS");
        for(String p:new String[]{"tsk","msg","doc","prj","req","trn","ana","fdb"})MODULES.put("/api/v1/"+p+"/","MODULE_WORK_HUB");}
    public FeatureAvailabilityInterceptor(SystemSettingsApi settings){this.settings=settings;}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
        String path=request.getRequestURI();
        if(settings.booleanValue("MAINTENANCE_MODE",false)&&!path.startsWith("/api/v1/system-settings")&&!CurrentUser.get().hasRole("SYS_ADMIN"))
            throw new ApiException(503,"ICCP-MAINTENANCE","The system is temporarily unavailable for maintenance.");
        MODULES.forEach((prefix,flag)->{if(path.startsWith(prefix)&&!settings.enabled(flag))throw new ApiException(503,"ICCP-FEATURE-DISABLED","This function is currently disabled by a system administrator.");});
        if(request.getContentType()!=null&&request.getContentType().toLowerCase().startsWith("multipart/")&&!settings.booleanValue("ALLOW_FILE_UPLOADS",true))
            throw new ApiException(503,"ICCP-UPLOADS-DISABLED","File uploads are currently disabled by a system administrator.");
        return true;
    }
}
