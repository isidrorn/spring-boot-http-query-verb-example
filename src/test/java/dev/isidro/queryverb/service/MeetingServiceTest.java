package dev.isidro.queryverb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.isidro.queryverb.domain.Calendar;
import dev.isidro.queryverb.domain.Meeting;
import dev.isidro.queryverb.domain.Slot;
import dev.isidro.queryverb.domain.User;
import dev.isidro.queryverb.repository.MeetingRepository;
import dev.isidro.queryverb.repository.SlotRepository;
import dev.isidro.queryverb.web.dto.MeetingCreateRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

    @Mock SlotRepository slotRepository;
    @Mock MeetingRepository meetingRepository;
    @InjectMocks MeetingService meetingService;

    static final Long ORGANIZER_ID        = 1L;
    static final Long PARTICIPANT_ID      = 2L;
    static final Long ORGANIZER_SLOT_ID   = 10L;
    static final Long PARTICIPANT_SLOT_ID = 20L;
    static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");
    static final Instant T1 = Instant.parse("2026-06-01T10:00:00Z");

    private Slot organizerSlot;
    private Slot participantSlot;

    @BeforeEach
    void setUp() {
        organizerSlot   = slotOwnedBy(ORGANIZER_ID,    T0, T1);
        participantSlot = slotOwnedBy(PARTICIPANT_ID,   T0, T1);  // same window → overlaps
    }

    // ── schedule ──────────────────────────────────────────────────────────────

    @Test
    void schedule_throwsNotFound_whenOrganizerSlotMissing() {
        when(slotRepository.findByIdForUpdate(ORGANIZER_SLOT_ID)).thenReturn(Optional.empty());

        assertStatus(HttpStatus.NOT_FOUND,
                () -> meetingService.schedule(ORGANIZER_ID, ORGANIZER_SLOT_ID, request()));
    }

    @Test
    void schedule_throwsForbidden_whenSlotBelongsToOtherUser() {
        when(slotRepository.findByIdForUpdate(ORGANIZER_SLOT_ID)).thenReturn(Optional.of(organizerSlot));

        // call with a different userId
        assertStatus(HttpStatus.FORBIDDEN,
                () -> meetingService.schedule(PARTICIPANT_ID, ORGANIZER_SLOT_ID, request()));
    }

    @Test
    void schedule_throwsConflict_whenOrganizerSlotNotFree() {
        Meeting existing = Meeting.builder().title("X").description("X").build();
        existing.addSlot(organizerSlot);   // marks BUSY

        when(slotRepository.findByIdForUpdate(ORGANIZER_SLOT_ID)).thenReturn(Optional.of(organizerSlot));

        assertStatus(HttpStatus.CONFLICT,
                () -> meetingService.schedule(ORGANIZER_ID, ORGANIZER_SLOT_ID, request()));
    }

    @Test
    void schedule_throwsConflict_whenParticipantSlotNotFree() {
        Meeting existing = Meeting.builder().title("X").description("X").build();
        existing.addSlot(participantSlot); // marks participant BUSY

        when(slotRepository.findByIdForUpdate(ORGANIZER_SLOT_ID)).thenReturn(Optional.of(organizerSlot));
        when(slotRepository.findByIdForUpdate(PARTICIPANT_SLOT_ID)).thenReturn(Optional.of(participantSlot));

        assertStatus(HttpStatus.CONFLICT,
                () -> meetingService.schedule(ORGANIZER_ID, ORGANIZER_SLOT_ID, request()));
    }

    @Test
    void schedule_throwsUnprocessable_whenParticipantSlotDisjoint() {
        Instant t2 = Instant.parse("2026-06-01T11:00:00Z");
        Instant t3 = Instant.parse("2026-06-01T12:00:00Z");
        Slot disjointSlot = slotOwnedBy(PARTICIPANT_ID, t2, t3); // no overlap with T0-T1

        when(slotRepository.findByIdForUpdate(ORGANIZER_SLOT_ID)).thenReturn(Optional.of(organizerSlot));
        when(slotRepository.findByIdForUpdate(PARTICIPANT_SLOT_ID)).thenReturn(Optional.of(disjointSlot));

        assertStatus(HttpStatus.UNPROCESSABLE_CONTENT,
                () -> meetingService.schedule(ORGANIZER_ID, ORGANIZER_SLOT_ID, request()));
    }

    @Test
    void schedule_createsMeeting_andMarksSlotsAsBusy() {
        when(slotRepository.findByIdForUpdate(ORGANIZER_SLOT_ID)).thenReturn(Optional.of(organizerSlot));
        when(slotRepository.findByIdForUpdate(PARTICIPANT_SLOT_ID)).thenReturn(Optional.of(participantSlot));
        when(meetingRepository.save(any())).thenAnswer(inv -> {
            Meeting m = inv.getArgument(0);
            ReflectionTestUtils.setField(m, "id", 100L);
            return m;
        });

        Meeting result = meetingService.schedule(ORGANIZER_ID, ORGANIZER_SLOT_ID, request());

        assertThat(result.getTitle()).isEqualTo("Team sync");
        assertThat(result.getSlots()).hasSize(2);
        assertThat(organizerSlot.isFree()).isFalse();
        assertThat(participantSlot.isFree()).isFalse();
        verify(meetingRepository).save(any(Meeting.class));
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void cancel_marksAllSlotsFreeAndDeletesMeeting() {
        Meeting meeting = Meeting.builder().title("M").description("D").build();
        ReflectionTestUtils.setField(meeting, "id", 99L);
        meeting.addSlot(organizerSlot);
        meeting.addSlot(participantSlot);

        when(slotRepository.findByIdForUpdate(ORGANIZER_SLOT_ID)).thenReturn(Optional.of(organizerSlot));

        meetingService.cancel(ORGANIZER_ID, ORGANIZER_SLOT_ID);

        assertThat(organizerSlot.isFree()).isTrue();
        assertThat(participantSlot.isFree()).isTrue();
        verify(meetingRepository).delete(meeting);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Slot slotOwnedBy(Long userId, Instant start, Instant end) {
        User user = new User("User " + userId, "user" + userId + "@test.com");
        ReflectionTestUtils.setField(user, "id", userId);
        Calendar cal = new Calendar(user);
        Slot slot = new Slot(start, end);
        cal.addSlot(slot);
        return slot;
    }

    private MeetingCreateRequest request() {
        return new MeetingCreateRequest("Team sync", "Weekly sync", List.of(PARTICIPANT_SLOT_ID));
    }

    private void assertStatus(HttpStatus expected, ThrowingRunnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(expected);
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run();
    }
}
