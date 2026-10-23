package tz.go.tirdo.iccp.com.web;

import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tz.go.tirdo.iccp.com.AnnouncementQueries;
import tz.go.tirdo.iccp.com.AnnouncementService;
import tz.go.tirdo.iccp.com.ComTypes;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.web.PageResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/com")
public class AnnouncementController {

    private final AnnouncementService service;
    private final AnnouncementQueries queries;

    public AnnouncementController(AnnouncementService service, AnnouncementQueries queries) {
        this.service = service;
        this.queries = queries;
    }

    private static String lang(String header) {
        String preferred = CurrentUser.get().language();
        if (header != null && header.toLowerCase().startsWith("sw")) {
            return "sw";
        }
        return header == null ? preferred : "en";
    }

    // ---------------------------------------------------------------- feed (all staff)

    @GetMapping("/feed")
    public PageResult<ComTypes.FeedItem> feed(@RequestParam(defaultValue = "all") String filter,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        return queries.feed(filter, lang(acceptLanguage), page, size);
    }

    @GetMapping("/feed/emergency")
    public List<ComTypes.FeedItem> emergency(@RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        return queries.emergencies(lang(acceptLanguage));
    }

    @GetMapping("/feed/{publicId}")
    public ComTypes.FeedDetail open(@PathVariable UUID publicId) {
        return queries.open(publicId);
    }

    @PostMapping("/feed/{publicId}/acknowledge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledge(@PathVariable UUID publicId, @RequestBody(required = false) Map<String, String> body) {
        service.acknowledge(publicId, body == null ? "WEB" : body.getOrDefault("channel", "WEB"));
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<InputStreamResource> download(@PathVariable long id) {
        AnnouncementQueries.Download d = queries.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(d.contentType()))
                .contentLength(d.size())
                .body(new InputStreamResource(d.stream()));
    }

    // ---------------------------------------------------------------- publishers and approvers

    @GetMapping("/announcements")
    public List<ComTypes.ManageItem> manage(@RequestParam(defaultValue = "mine") String box) {
        return queries.manageList(box);
    }

    @PostMapping("/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> create(@Valid @RequestBody ComTypes.AnnouncementInput input) {
        return Map.of("publicId", service.create(input));
    }

    @GetMapping("/announcements/{publicId}")
    public ComTypes.ManageDetail detail(@PathVariable UUID publicId) {
        return queries.manageDetail(publicId);
    }

    @PutMapping("/announcements/{publicId}")
    public ComTypes.ManageDetail update(@PathVariable UUID publicId, @Valid @RequestBody ComTypes.AnnouncementInput input) {
        service.update(publicId, input);
        return queries.manageDetail(publicId);
    }

    @PostMapping("/announcements/{publicId}/submit")
    public Map<String, String> submit(@PathVariable UUID publicId) {
        return Map.of("status", service.submit(publicId));
    }

    @PostMapping("/announcements/{publicId}/approve")
    public Map<String, String> approve(@PathVariable UUID publicId, @Valid @RequestBody(required = false) ComTypes.DecisionInput body) {
        return Map.of("status", service.decide(publicId, "APPROVED", body == null ? null : body.comment()));
    }

    @PostMapping("/announcements/{publicId}/reject")
    public Map<String, String> reject(@PathVariable UUID publicId, @Valid @RequestBody ComTypes.DecisionInput body) {
        return Map.of("status", service.decide(publicId, "REJECTED", body.comment()));
    }

    @PostMapping("/announcements/{publicId}/return")
    public Map<String, String> returnForEdit(@PathVariable UUID publicId, @Valid @RequestBody ComTypes.DecisionInput body) {
        return Map.of("status", service.decide(publicId, "RETURNED", body.comment()));
    }

    @PostMapping("/announcements/{publicId}/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable UUID publicId, @Valid @RequestBody ComTypes.DecisionInput body) {
        service.withdraw(publicId, body.comment());
    }

    @PostMapping("/announcements/{publicId}/amend")
    public ComTypes.ManageDetail amend(@PathVariable UUID publicId, @Valid @RequestBody ComTypes.AnnouncementInput input) {
        service.amend(publicId, input);
        return queries.manageDetail(publicId);
    }

    @PostMapping(value = "/announcements/{publicId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ComTypes.ManageDetail attach(@PathVariable UUID publicId, @RequestPart("file") MultipartFile file) throws IOException {
        service.addAttachment(publicId, file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return queries.manageDetail(publicId);
    }

    @GetMapping("/announcements/{publicId}/reach")
    public ComTypes.Reach reach(@PathVariable UUID publicId) {
        return queries.reach(publicId);
    }

    @PostMapping("/audience/preview")
    public Map<String, Long> preview(@Valid @RequestBody ComTypes.AudiencePreview body) {
        return Map.of("count", service.previewAudience(body.audiences()));
    }
}
