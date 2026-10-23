package tz.go.tirdo.iccp.dir.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tz.go.tirdo.iccp.dir.DirectoryQueries;
import tz.go.tirdo.iccp.dir.HrImportService;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.platform.web.PageResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dir")
public class DirectoryController {

    private final DirectoryQueries queries;
    private final HrImportService imports;

    public DirectoryController(DirectoryQueries queries, HrImportService imports) {
        this.queries = queries;
        this.imports = imports;
    }

    /** FR-DIR-07: search by name, unit, title and expertise. */
    @GetMapping("/staff")
    public PageResult<DirectoryQueries.StaffSummary> search(@RequestParam(required = false) String q,
                                                            @RequestParam(required = false) String unit,
                                                            @RequestParam(required = false) Integer page,
                                                            @RequestParam(required = false) Integer size) {
        return queries.search(q, unit, page, size);
    }

    @GetMapping("/staff/{publicId}")
    public DirectoryQueries.StaffProfile profile(@PathVariable UUID publicId) {
        return queries.profile(publicId);
    }

    @GetMapping("/me")
    public DirectoryQueries.StaffProfile me() {
        return queries.profile(CurrentUser.get().staffPublicId());
    }

    /** FR-DIR-05: staff edit only photo, biography, expertise and contact preferences. */
    @PatchMapping("/me")
    public DirectoryQueries.StaffProfile updateMe(@RequestBody DirectoryQueries.ProfileUpdate update) {
        return queries.updateMe(update);
    }

    @GetMapping("/units/tree")
    public List<DirectoryQueries.UnitNode> tree() {
        return queries.unitTree();
    }

    // ---------------------------------------------------------------- HR officers

    @PostMapping(value = "/imports", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('HR_OFFICER')")
    public HrImportService.ImportResult importFile(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw ApiException.badRequest("ICCP-DIR-030", "error.emptyFile");
        }
        return imports.importFile(file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping("/imports")
    @PreAuthorize("hasAnyRole('HR_OFFICER','SYS_ADMIN')")
    public List<DirectoryQueries.HrBatch> batches() {
        return queries.batches();
    }

    @GetMapping("/imports/{id}/exceptions")
    @PreAuthorize("hasAnyRole('HR_OFFICER','SYS_ADMIN')")
    public List<DirectoryQueries.HrException> exceptions(@PathVariable long id) {
        return queries.exceptions(id);
    }

    @PostMapping("/exceptions/{id}/close")
    @PreAuthorize("hasRole('HR_OFFICER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeException(@PathVariable long id, @RequestBody Map<String, String> body) {
        queries.closeException(id, body.getOrDefault("status", "RESOLVED"));
    }

    @GetMapping("/acting")
    public List<DirectoryQueries.ActingAppointment> acting() {
        return queries.actingAppointments();
    }

    @PostMapping("/acting")
    @PreAuthorize("hasRole('HR_OFFICER')")
    @ResponseStatus(HttpStatus.CREATED)
    public DirectoryQueries.ActingAppointment createActing(@RequestBody DirectoryQueries.ActingRequest request) {
        return queries.createActing(request);
    }

    @DeleteMapping("/acting/{id}")
    @PreAuthorize("hasRole('HR_OFFICER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endActing(@PathVariable long id) {
        queries.endActing(id);
    }
}
