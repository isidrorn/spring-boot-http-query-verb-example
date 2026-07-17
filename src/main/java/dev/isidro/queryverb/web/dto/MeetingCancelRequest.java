package dev.isidro.queryverb.web.dto;

import jakarta.validation.constraints.NotNull;

/** Body of DELETE /api/meetings/{meetingId} — the caller must be the meeting's organizer. */
public record MeetingCancelRequest(
        @NotNull Long userId
) {}
