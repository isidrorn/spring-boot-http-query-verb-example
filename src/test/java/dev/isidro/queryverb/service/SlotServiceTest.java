package dev.isidro.queryverb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.isidro.queryverb.domain.Calendar;
import dev.isidro.queryverb.domain.Meeting;
import dev.isidro.queryverb.domain.Slot;
import dev.isidro.queryverb.domain.SlotStatus;
import dev.isidro.queryverb.domain.User;
import dev.isidro.queryverb.repository.CalendarRepository;
import dev.isidro.queryverb.repository.SlotRepository;
import dev.isidro.queryverb.web.dto.SlotCreateRequest;
import dev.isidro.queryverb.web.dto.SlotUpdateRequest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SlotServiceTest {

    @Mock SlotRepository slotRepository;
    @Mock CalendarRepository calendarRepository;
    @InjectMocks SlotService slotService;

    static final Long USER_ID  = 1L;
    static final Long SLOT_ID  = 10L;
    static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");
    static final Instant T1 = Instant.parse("2026-06-01T10:00:00Z");
    static final Instant T2 = Instant.parse("2026-06-01T11:00:00Z");

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_throwsBadRequest_whenStartAfterEnd() {
        assertThatThrownBy(() -> slotService.create(USER_ID, new SlotCreateRequest(T1, T0)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_throwsConflict_whenOverlapExists() {
        when(slotRepository.existsOverlap(USER_ID, T0, T1, null)).thenReturn(true);
        assertThatThrownBy(() -> slotService.create(USER_ID, new SlotCreateRequest(T0, T1)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void create_savesSlot_whenValid() {
        when(slotRepository.existsOverlap(USER_ID, T0, T1, null)).thenReturn(false);
        User user = new User("Test", "test@test.com");
        Calendar calendar = new Calendar(user);
        when(calendarRepository.findByOwnerId(USER_ID)).thenReturn(Optional.of(calendar));
        when(slotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Slot result = slotService.create(USER_ID, new SlotCreateRequest(T0, T1));

        assertThat(result.getStartTime()).isEqualTo(T0);
        assertThat(result.getStatus()).isEqualTo(SlotStatus.FREE);
        verify(slotRepository).save(any(Slot.class));
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_throwsConflict_whenSlotHasMeeting() {
        Slot slot = slotWithMeeting();
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void update_marksSlotBusy_whenRequested() {
        Slot slot = new Slot(T0, T1);
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));
        when(slotRepository.existsOverlap(eq(USER_ID), any(), any(), eq(SLOT_ID))).thenReturn(false);
        when(slotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Slot result = slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(null, null, SlotStatus.BUSY));

        assertThat(result.getStatus()).isEqualTo(SlotStatus.BUSY);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_throwsConflict_whenSlotHasMeeting() {
        Slot slot = slotWithMeeting();
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> slotService.delete(USER_ID, SLOT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void delete_deletesSlot_whenFree() {
        Slot slot = new Slot(T0, T1);
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));

        slotService.delete(USER_ID, SLOT_ID);

        verify(slotRepository).delete(slot);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Slot slotWithMeeting() {
        Slot slot = new Slot(T0, T1);
        Meeting meeting = Meeting.builder().title("M").description("D").build();
        meeting.addSlot(slot);
        return slot;
    }
}
