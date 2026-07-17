package dev.isidro.queryverb.web.dto;

import dev.isidro.queryverb.domain.SlotStatus;
import java.time.Instant;

/**
 * PATCH payload: all fields optional — only non-null fields are applied. endTime is never
 * accepted here either: rescheduling startTime recomputes endTime from the system slot duration.
 */
public record SlotUpdateRequest(
        Instant startTime,
        SlotStatus status
) {}
