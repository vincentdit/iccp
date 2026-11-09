package tz.go.tirdo.iccp.fin.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tz.go.tirdo.iccp.fin.FinTypes;
import tz.go.tirdo.iccp.fin.FinanceBatchService;
import tz.go.tirdo.iccp.fin.FinanceImportService;
import tz.go.tirdo.iccp.fin.FinancialDiaryService;
import tz.go.tirdo.iccp.platform.security.CurrentUser;
import tz.go.tirdo.iccp.platform.security.Roles;
import tz.go.tirdo.iccp.platform.web.ApiException;
import tz.go.tirdo.iccp.platform.web.PageResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Financial Diary (staff) and Finance workspace endpoints — Doc 07 §FIN. */
@RestController
@RequestMapping("/api/v1/fin")
public class FinanceController {

    private final FinancialDiaryService diary;
    private final FinanceImportService imports;
    private final FinanceBatchService batches;

    public FinanceController(FinancialDiaryService diary, FinanceImportService imports, FinanceBatchService batches) {
        this.diary = diary;
        this.imports = imports;
        this.batches = batches;
    }

    // ---------------------------------------------------------------- staff diary

    @GetMapping("/me/summary")
    public List<FinTypes.Summary> summary() {
        return diary.summary();
    }

    @GetMapping("/me/claims")
    public PageResult<FinTypes.ClaimLine> claims(@RequestParam(required = false) String status, @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return diary.claims(status, type, q, page, size);
    }

    @GetMapping("/me/claims/{id}")
    public FinTypes.ClaimDetail claim(@PathVariable UUID id) {
        return diary.claim(id);
    }

    @PostMapping("/me/claims/{id}/queries")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> raise(@PathVariable UUID id, @Valid @RequestBody FinTypes.NewQuery in) {
        return Map.of("publicId", diary.raise(id, in));
    }

    @GetMapping("/me/queries")
    public List<FinTypes.QuerySummary> myQueries() {
        return diary.myQueries();
    }

    // ---------------------------------------------------------------- query thread (staff owner or Finance)

    @GetMapping("/queries/{id}")
    public FinTypes.QueryDetail query(@PathVariable UUID id) {
        return diary.query(id);
    }

    @PostMapping("/queries/{id}/messages")
    public FinTypes.QueryDetail post(@PathVariable UUID id, @Valid @RequestBody FinTypes.MessageInput in) {
        return diary.post(id, in);
    }

    @PostMapping("/queries/{id}/confirm")
    public FinTypes.QueryDetail confirm(@PathVariable UUID id) {
        return diary.confirm(id);
    }

    @PostMapping("/queries/{id}/reopen")
    public FinTypes.QueryDetail reopen(@PathVariable UUID id, @Valid @RequestBody FinTypes.MessageInput in) {
        return diary.reopen(id, in);
    }

    @PostMapping("/queries/{id}/escalate")
    public FinTypes.QueryDetail escalate(@PathVariable UUID id) {
        return diary.escalateByFinance(id);
    }

    @PostMapping("/queries/{id}/close")
    public FinTypes.QueryDetail close(@PathVariable UUID id) {
        return diary.close(id);
    }

    // ---------------------------------------------------------------- Finance workspace

    @GetMapping("/desk")
    public PageResult<FinTypes.DeskItem> desk(@RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return diary.desk(status, page, size);
    }

    @GetMapping("/dashboard")
    public FinTypes.Dashboard dashboard() {
        return batches.dashboard();
    }

    @PostMapping("/imports")
    @ResponseStatus(HttpStatus.CREATED)
    public FinTypes.ImportResult importFile(@RequestPart("file") MultipartFile file) throws IOException {
        // Finance reviewers may also load a diary export. The batch service still enforces
        // maker-checker: whoever uploads a batch cannot review or publish that same batch.
        CurrentUser.require(Roles.FIN_OFFICER, Roles.FIN_REVIEWER);
        if (file.isEmpty()) {
            throw ApiException.badRequest("ICCP-FIN-021", "fin.emptyFile");
        }
        return imports.importFile(file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping("/batches")
    public PageResult<FinTypes.Batch> batches(@RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return batches.batches(status, page, size);
    }

    @GetMapping("/batches/{id}")
    public FinTypes.Batch batch(@PathVariable UUID id) {
        return batches.batch(id);
    }

    @GetMapping("/batches/{id}/quarantine")
    public List<FinTypes.QuarantineItem> quarantine(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean openOnly) {
        return batches.quarantine(id, openOnly);
    }

    @PostMapping("/batches/{id}/approve")
    public FinTypes.Batch approve(@PathVariable UUID id) {
        return batches.approve(id);
    }

    @PostMapping("/batches/{id}/reject")
    public FinTypes.Batch reject(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return batches.reject(id, body.get("reason"));
    }

    @PostMapping("/quarantine/{itemId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeQuarantine(@PathVariable long itemId, @Valid @RequestBody FinTypes.CloseQuarantine in) {
        batches.closeQuarantine(itemId, in);
    }

    @GetMapping("/allocations/pending")
    public List<FinTypes.PendingAllocation> pending() {
        return batches.pendingAllocations();
    }

    @PostMapping("/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> allocate(@Valid @RequestBody FinTypes.ManualAllocation in) {
        return Map.of("id", batches.allocate(in));
    }

    @PostMapping("/allocations/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approveAllocation(@PathVariable long id) {
        batches.approveAllocation(id);
    }

    @PostMapping("/allocations/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rejectAllocation(@PathVariable long id) {
        batches.rejectAllocation(id);
    }
}
