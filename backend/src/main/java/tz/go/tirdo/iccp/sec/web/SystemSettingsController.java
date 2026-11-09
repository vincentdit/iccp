package tz.go.tirdo.iccp.sec.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.sec.SystemSettingsService;

@RestController
@RequestMapping("/api/v1/system-settings")
public class SystemSettingsController {
    private final SystemSettingsService settings;
    public SystemSettingsController(SystemSettingsService settings){this.settings=settings;}
    public record FeatureUpdate(boolean enabled){}
    public record SettingUpdate(JsonNode value){}

    @GetMapping("/public") public SystemSettingsService.Snapshot publicSettings(){return settings.publicSnapshot();}
    @GetMapping public SystemSettingsService.Snapshot all(){CurrentUser.require("SYS_ADMIN");return settings.all();}
    @PutMapping("/features/{code}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feature(@PathVariable String code,@RequestBody FeatureUpdate in){CurrentUser.require("SYS_ADMIN");settings.updateFeature(code,in.enabled());}
    @PutMapping("/values/{code}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setting(@PathVariable String code,@RequestBody SettingUpdate in){CurrentUser.require("SYS_ADMIN");settings.updateSetting(code,in.value());}
}
