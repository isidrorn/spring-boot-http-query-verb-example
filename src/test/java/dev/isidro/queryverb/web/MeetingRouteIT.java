package dev.isidro.queryverb.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.isidro.queryverb.TestSupport;
import dev.isidro.queryverb.repository.CalendarRepository;
import dev.isidro.queryverb.repository.MeetingRepository;
import dev.isidro.queryverb.repository.SlotRepository;
import dev.isidro.queryverb.repository.UserRepository;
import dev.isidro.queryverb.web.dto.MeetingCreateRequest;
import dev.isidro.queryverb.web.dto.MeetingResponse;
import dev.isidro.queryverb.web.dto.SlotResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class MeetingRouteIT {

    static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");
    static final Instant T1 = Instant.parse("2026-06-01T10:00:00Z");

    @Autowired TestRestTemplate    restTemplate;
    @Autowired SlotRepository      slotRepository;
    @Autowired MeetingRepository   meetingRepository;
    @Autowired CalendarRepository  calendarRepository;
    @Autowired UserRepository      userRepository;

    Long aliceId;
    Long bobId;
    Long aliceSlotId;
    Long bobSlotId;

    @BeforeEach
    void seed() {
        TestSupport.cleanUp(slotRepository, meetingRepository, calendarRepository, userRepository);
        aliceId     = TestSupport.seedUser(userRepository, calendarRepository, "Alice", "alice@test.com");
        bobId       = TestSupport.seedUser(userRepository, calendarRepository, "Bob",   "bob@test.com");
        aliceSlotId = TestSupport.seedSlot(slotRepository, calendarRepository, aliceId, T0, T1);
        bobSlotId   = TestSupport.seedSlot(slotRepository, calendarRepository, bobId,   T0, T1);   // same window → valid participant slot
    }

    @Test
    void schedule_returns201_andBothSlotsAreBusy() {
        var req = new MeetingCreateRequest("Standup", "Daily standup", List.of(bobSlotId));

        ResponseEntity<MeetingResponse> res = restTemplate.postForEntity(
                "/api/users/{uid}/slots/{sid}/meeting",
                req, MeetingResponse.class, aliceId, aliceSlotId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MeetingResponse body = res.getBody();
        assertThat(body.title()).isEqualTo("Standup");
        assertThat(body.slots()).hasSize(2);
        assertThat(body.slots()).extracting(SlotResponse::meetingId).doesNotContainNull();
    }

    @Test
    void getMeeting_returns200_afterScheduling() {
        var req = new MeetingCreateRequest("Standup", "Daily standup", List.of(bobSlotId));
        MeetingResponse created = restTemplate.postForEntity(
                "/api/users/{uid}/slots/{sid}/meeting",
                req, MeetingResponse.class, aliceId, aliceSlotId).getBody();

        ResponseEntity<MeetingResponse> fetched = restTemplate.getForEntity(
                "/api/meetings/{mid}", MeetingResponse.class, created.id());

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().id()).isEqualTo(created.id());
    }

    @Test
    void cancel_returns204_andSlotsAreFreeAgain() {
        var req = new MeetingCreateRequest("Standup", "Daily standup", List.of(bobSlotId));
        restTemplate.postForEntity("/api/users/{uid}/slots/{sid}/meeting",
                req, MeetingResponse.class, aliceId, aliceSlotId);

        restTemplate.delete("/api/users/{uid}/slots/{sid}/meeting", aliceId, aliceSlotId);

        // Alice's slot should be FREE again
        ResponseEntity<SlotResponse> slot = restTemplate.getForEntity(
                "/api/users/{uid}/slots/{sid}", SlotResponse.class, aliceId, aliceSlotId);
        assertThat(slot.getBody().meetingId()).isNull();
    }

    @Test
    void schedule_returns409_whenSlotAlreadyBooked() {
        var req = new MeetingCreateRequest("M1", "First", List.of(bobSlotId));
        restTemplate.postForEntity("/api/users/{uid}/slots/{sid}/meeting",
                req, MeetingResponse.class, aliceId, aliceSlotId);

        // Try to book the same slot again
        Long anotherBobSlotId = TestSupport.seedSlot(slotRepository, calendarRepository, bobId, T0, T1);
        var req2 = new MeetingCreateRequest("M2", "Second", List.of(anotherBobSlotId));
        ResponseEntity<String> conflict = restTemplate.postForEntity(
                "/api/users/{uid}/slots/{sid}/meeting",
                req2, String.class, aliceId, aliceSlotId);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
