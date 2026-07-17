package dev.isidro.queryverb.service;

import dev.isidro.queryverb.config.SlotDurationConfig;
import dev.isidro.queryverb.domain.Calendar;
import dev.isidro.queryverb.domain.Slot;
import dev.isidro.queryverb.domain.SlotStatus;
import dev.isidro.queryverb.repository.CalendarRepository;
import dev.isidro.queryverb.repository.SlotRepository;
import dev.isidro.queryverb.web.dto.SlotBulkCreateRequest;
import dev.isidro.queryverb.web.dto.SlotQueryFilter;
import dev.isidro.queryverb.web.dto.SlotUpdateRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class SlotService {

    private final SlotRepository slotRepository;
    private final CalendarRepository calendarRepository;
    private final SlotDurationConfig slotDurationConfig;

    @Transactional(readOnly = true)
    public List<Slot> query(Long userId, SlotQueryFilter filter) {
        return slotRepository.search(userId, filter.status(), filter.from(), filter.to());
    }

    /**
     * Creates every requested slot in one transaction — any invalid/conflicting startTime fails
     * the whole batch (nothing is partially created), per the "elegir fallo en bloque" decision
     * in the design log.
     */
    public List<Slot> create(Long userId, SlotBulkCreateRequest request) {
        long durationSeconds = slotDurationConfig.getSlotDurationMinutes() * 60L;

        Set<Instant> requestedStarts = new HashSet<>();
        for (Instant start : request.startTimes()) {
            if (start == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startTimes must not contain null entries");
            }
            if (start.getEpochSecond() % durationSeconds != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "startTime %s is not aligned to the %d-minute slot grid"
                                .formatted(start, slotDurationConfig.getSlotDurationMinutes()));
            }
            if (!requestedStarts.add(start)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate startTime in request: " + start);
            }
        }

        Calendar calendar = calendarRepository.findByOwnerIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Calendar not found for userId=" + userId));

        List<Slot> newSlots = new ArrayList<>();
        for (Instant start : request.startTimes()) {
            Instant end = start.plusSeconds(durationSeconds);
            if (slotRepository.existsOverlap(userId, start, end, null)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Slot at %s overlaps with an existing slot".formatted(start));
            }
            newSlots.add(new Slot(calendar, start, end));
        }

        return slotRepository.saveAll(newSlots);
    }

    public Slot update(Long userId, Long slotId, SlotUpdateRequest request) {
        Slot slot = requireOwned(userId, slotId);

        if (slot.hasConfirmedMeeting()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot modify a slot booked in a confirmed meeting");
        }

        long durationSeconds = slotDurationConfig.getSlotDurationMinutes() * 60L;
        Instant newStart = request.startTime() != null ? request.startTime() : slot.getStartTime();
        Instant newEnd = request.startTime() != null ? newStart.plusSeconds(durationSeconds) : slot.getEndTime();

        if (request.startTime() != null && newStart.getEpochSecond() % durationSeconds != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "startTime %s is not aligned to the %d-minute slot grid"
                            .formatted(newStart, slotDurationConfig.getSlotDurationMinutes()));
        }

        // Serializes the overlap-check-then-write sequence per user — see
        // CalendarRepository.findByOwnerIdForUpdate.
        calendarRepository.findByOwnerIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Calendar not found for userId=" + userId));

        boolean overlap = slotRepository.existsOverlap(userId, newStart, newEnd, slotId);
        if (overlap) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Updated slot would overlap with an existing slot");
        }

        if (request.status() == SlotStatus.FREE) slot.markFree();
        else if (request.status() == SlotStatus.BUSY) slot.markBusy();

        slot.reschedule(newStart, newEnd);
        return slotRepository.save(slot);
    }

    public void delete(Long userId, Long slotId) {
        Slot slot = requireOwned(userId, slotId);
        if (slot.hasConfirmedMeeting()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete a slot booked in a confirmed meeting");
        }
        slotRepository.delete(slot);
    }

    @Transactional(readOnly = true)
    public Slot requireOwned(Long userId, Long slotId) {
        return slotRepository.findByUserIdAndSlotId(userId, slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Slot %d not found for userId=%d".formatted(slotId, userId)));
    }
}
