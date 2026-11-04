package tz.go.tirdo.iccp.ntf.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.iccp.ntf.NotificationService;
import tz.go.tirdo.iccp.ntf.PreferenceService;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.web.PageResult;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ntf")
public class NotificationController {

    private final NotificationService notifications;
    private final PreferenceService preferences;

    public NotificationController(NotificationService notifications, PreferenceService preferences) {
        this.notifications = notifications;
        this.preferences = preferences;
    }

    @GetMapping("/inbox")
    public PageResult<NotificationService.InboxItem> inbox(@RequestParam(defaultValue = "false") boolean unread,
                                                           @RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size,
                                                           @RequestHeader(name = "Accept-Language", defaultValue = "en") String language) {
        return notifications.inbox(CurrentUser.get().staffId(), unread, page, size, language);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notifications.unreadCount(CurrentUser.get().staffId()));
    }

    @PostMapping("/{publicId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID publicId) {
        notifications.markRead(CurrentUser.get().staffId(), publicId);
    }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll() {
        return Map.of("updated", notifications.markAllRead(CurrentUser.get().staffId()));
    }

    @GetMapping("/preferences")
    public PreferenceService.Preferences getPreferences() {
        return preferences.get(CurrentUser.get().staffId());
    }

    @PutMapping("/preferences")
    public PreferenceService.Preferences savePreferences(@RequestBody PreferenceService.Preferences body) {
        return preferences.save(CurrentUser.get().staffId(), body);
    }
}
