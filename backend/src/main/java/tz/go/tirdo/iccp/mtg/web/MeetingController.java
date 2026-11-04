package tz.go.tirdo.iccp.mtg.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tz.go.tirdo.iccp.mtg.MeetingService;
import tz.go.tirdo.iccp.mtg.MeetingTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/mtg")
public class MeetingController {
    private final MeetingService service;
    public MeetingController(MeetingService service){ this.service=service; }
    @GetMapping("/types") public List<MeetingTypes.Type> types(){ return service.types(); }
    @GetMapping("/meetings") public List<MeetingTypes.Summary> meetings(){ return service.list(); }
    @GetMapping("/meetings/{id}") public MeetingTypes.Detail detail(@PathVariable UUID id){ return service.detail(id); }
    @PostMapping("/meetings") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,UUID> create(@Valid @RequestBody MeetingTypes.Create input){ return Map.of("publicId",service.create(input)); }
    @PostMapping("/meetings/{id}/rsvp") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rsvp(@PathVariable UUID id,@Valid @RequestBody MeetingTypes.Rsvp input){ service.rsvp(id,input.response()); }
    @PostMapping("/meetings/{id}/attendance") @ResponseStatus(HttpStatus.NO_CONTENT) public void attendance(@PathVariable UUID id,@Valid @RequestBody MeetingTypes.Attendance in){service.attendance(id,in);}
    @GetMapping("/meetings/{id}/minutes") public MeetingTypes.Minutes minutes(@PathVariable UUID id){return service.minutes(id);}
    @PutMapping("/meetings/{id}/minutes") @ResponseStatus(HttpStatus.NO_CONTENT) public void save(@PathVariable UUID id,@Valid @RequestBody MeetingTypes.MinutesInput in){service.saveMinutes(id,in);}
    @PostMapping("/meetings/{id}/minutes/submit") @ResponseStatus(HttpStatus.NO_CONTENT) public void submit(@PathVariable UUID id){service.submitMinutes(id);}
    @PostMapping("/meetings/{id}/minutes/approve") @ResponseStatus(HttpStatus.NO_CONTENT) public void approve(@PathVariable UUID id){service.approveMinutes(id);}
}
