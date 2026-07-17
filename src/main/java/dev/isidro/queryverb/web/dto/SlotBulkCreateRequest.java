package dev.isidro.queryverb.web.dto;

import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

/**
 * endTime is not part of the request — it's always startTime + the system's
 * scheduling.slot-duration-minutes, computed in SlotService.create().
 */
public record SlotBulkCreateRequest(
        @NotEmpty List<Instant> startTimes
) {}
