package dev.isidro.queryverb.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * Creates a meeting in PROPOSED status with no slots booked yet — booking only happens once
 * every REQUIRED participant votes YES, see MeetingService.vote/confirm.
 */
public record MeetingCreateRequest(
        @NotBlank String title,
        String description,
        @NotNull Long organizerUserId,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        List<Long> requiredParticipantUserIds,
        List<Long> optionalParticipantUserIds
) {
    public MeetingCreateRequest {
        requiredParticipantUserIds = requiredParticipantUserIds == null ? List.of() : requiredParticipantUserIds;
        optionalParticipantUserIds = optionalParticipantUserIds == null ? List.of() : optionalParticipantUserIds;
    }
}
