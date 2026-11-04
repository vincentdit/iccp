package tz.go.tirdo.iccp.mtg;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class MeetingTypes {
    private MeetingTypes() {}

    public record Type(String code, String nameEn, String nameSw, String confidentiality, int quorumPercent) {}
    public record Summary(UUID publicId, String title, String typeCode, String typeNameEn, String typeNameSw,
                          OffsetDateTime startsAt, OffsetDateTime endsAt, String mode, String venue,
                          String onlineLink, String status, String confidentiality, String chairName,
                          String secretaryName, int participantCount, String myRole, String myRsvp) {}
    public record Participant(UUID publicId, String displayName, String positionTitle, String unitName,
                              String role, String rsvp, String attendance) {}
    public record Agenda(short itemNo, String title, Integer minutesAllocated) {}
    public record Detail(Summary meeting, List<Participant> participants, List<Agenda> agenda) {}
    public record Create(@NotBlank @Size(max=240) String title, @NotBlank String typeCode,
                         @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt,
                         @NotBlank String mode, @Size(max=240) String venue, @Size(max=500) String onlineLink,
                         @NotNull UUID chairStaffPublicId, @NotNull UUID secretaryStaffPublicId,
                         List<UUID> participantStaffPublicIds, List<@NotBlank @Size(max=240) String> agenda) {}
    public record Rsvp(@NotBlank String response) {}
    public record Attendance(@NotNull UUID staffPublicId, @NotBlank String status) {}
    public record ResolutionInput(@NotBlank @Size(max=2000) String text, UUID ownerStaffPublicId, LocalDate dueOn) {}
    public record MinutesInput(@NotBlank @Size(max=20000) String summary, List<ResolutionInput> resolutions) {}
    public record Resolution(UUID publicId, String resolutionNo, String text, UUID ownerStaffPublicId, String ownerName, LocalDate dueOn, String status) {}
    public record Minutes(Integer versionNo, String status, String summary, List<Resolution> resolutions, boolean canEdit, boolean canSubmit, boolean canApprove) {}
}
