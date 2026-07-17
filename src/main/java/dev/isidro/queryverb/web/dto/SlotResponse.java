package dev.isidro.queryverb.web.dto;

import dev.isidro.queryverb.domain.SlotStatus;
import java.time.Instant;
import java.util.List;

public record SlotResponse(
        Long id,
        Instant startTime,
        Instant endTime,
        SlotStatus status,
        List<Long> meetingIds
) {}
