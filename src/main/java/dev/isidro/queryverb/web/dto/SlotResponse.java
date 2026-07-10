package dev.isidro.queryverb.web.dto;

import dev.isidro.queryverb.domain.SlotStatus;
import java.time.Instant;

public record SlotResponse(
        Long id,
        Long userId,
        Instant startTime,
        Instant endTime,
        SlotStatus status,
        Long meetingId
) {}
