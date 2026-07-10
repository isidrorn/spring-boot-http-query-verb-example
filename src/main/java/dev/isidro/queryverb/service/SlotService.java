package dev.isidro.queryverb.service;

import dev.isidro.queryverb.domain.Calendar;
import dev.isidro.queryverb.domain.Slot;
import dev.isidro.queryverb.domain.SlotStatus;
import dev.isidro.queryverb.repository.CalendarRepository;
import dev.isidro.queryverb.repository.SlotRepository;
import dev.isidro.queryverb.web.dto.SlotCreateRequest;
import dev.isidro.queryverb.web.dto.SlotQueryFilter;
import dev.isidro.queryverb.web.dto.SlotUpdateRequest;
import java.util.List;
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

    @Transactional(readOnly = true)
    public List<Slot> query(Long userId, SlotQueryFilter filter) {
        return slotRepository.search(userId, filter.status(), filter.from(), filter.to());
    }

    public Slot create(Long userId, SlotCreateRequest request) {
        if (request.startTime().isAfter(request.endTime()) || request.startTime().equals(request.endTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startTime must be before endTime");
        }

        Calendar calendar = calendarRepository.findByOwnerIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Calendar not found for userId=" + userId));

        boolean overlap = slotRepository.existsOverlap(userId, request.startTime(), request.endTime(), null);
        if (overlap) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot overlaps with an existing slot");
        }

        Slot slot = new Slot(calendar, request.startTime(), request.endTime());
        return slotRepository.save(slot);
    }

    public Slot update(Long userId, Long slotId, SlotUpdateRequest request) {
        Slot slot = requireOwned(userId, slotId);

        if (slot.getMeeting() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot modify a slot that is booked as a meeting");
        }

        // Build the effective interval after applying partial changes
        var newStart = request.startTime() != null ? request.startTime() : slot.getStartTime();
        var newEnd   = request.endTime()   != null ? request.endTime()   : slot.getEndTime();

        if (newStart.isAfter(newEnd) || newStart.equals(newEnd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startTime must be before endTime");
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
        if (slot.getMeeting() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete a slot that is booked as a meeting");
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
