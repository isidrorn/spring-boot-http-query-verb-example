package io.irn.minidoodle.web;

import io.irn.minidoodle.TestSupport;
import io.irn.minidoodle.domain.SlotStatus;
import io.irn.minidoodle.repository.CalendarRepository;
import io.irn.minidoodle.repository.MeetingParticipantRepository;
import io.irn.minidoodle.repository.MeetingRepository;
import io.irn.minidoodle.repository.SlotRepository;
import io.irn.minidoodle.repository.UserRepository;
import io.irn.minidoodle.web.dto.SlotBulkCreateRequest;
import io.irn.minidoodle.web.dto.SlotQueryFilter;
import io.irn.minidoodle.web.dto.SlotResponse;
import io.irn.minidoodle.web.dto.SlotUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class SlotRouteIT {

    static final HttpMethod QUERY = HttpMethod.valueOf("QUERY");

    // 30-minute grid (default scheduling.slot-duration-minutes)
    static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");
    static final Instant T1 = Instant.parse("2026-06-01T09:30:00Z");
    static final Instant T2 = Instant.parse("2026-06-01T10:00:00Z");
    static final Instant T3 = Instant.parse("2026-06-01T10:30:00Z");

    @Autowired TestRestTemplate    restTemplate;
    @Autowired SlotRepository      slotRepository;
    @Autowired MeetingRepository   meetingRepository;
    @Autowired MeetingParticipantRepository meetingParticipantRepository;
    @Autowired CalendarRepository  calendarRepository;
    @Autowired UserRepository      userRepository;

    Long userId;
    Long slotId;

    @BeforeEach
    void seed() {
        TestSupport.cleanUp(slotRepository, meetingRepository, meetingParticipantRepository, calendarRepository, userRepository);
        userId = TestSupport.seedUser(userRepository, calendarRepository, "Alice", "alice@test.com");
        slotId = TestSupport.seedSlot(slotRepository, calendarRepository, userId, T0, T1);
    }

    // ── GET (list) ────────────────────────────────────────────────────────────

    @Test
    void listSlots_returns200_withSeededSlot() {
        ResponseEntity<SlotResponse[]> res = restTemplate.getForEntity(
                "/api/users/{uid}/slots", SlotResponse[].class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).hasSize(1);
        assertThat(res.getBody()[0].status()).isEqualTo(SlotStatus.FREE);
        assertThat(res.getBody()[0].meetingIds()).isEmpty();
    }

    // ── QUERY (filter by body) ────────────────────────────────────────────────

    @Test
    void querySlots_filtersByFreeStatus_returnsMatch() {
        ResponseEntity<SlotResponse[]> res = doQuery(userId, new SlotQueryFilter(SlotStatus.FREE, null, null));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).hasSize(1);
    }

    @Test
    void querySlots_filtersByBusyStatus_returnsEmpty() {
        ResponseEntity<SlotResponse[]> res = doQuery(userId, new SlotQueryFilter(SlotStatus.BUSY, null, null));

        assertThat(res.getBody()).isEmpty();
    }

    @Test
    void querySlots_filtersByTimeRange_returnsMatch() {
        TestSupport.seedSlot(slotRepository, calendarRepository, userId, T2, T3);  // second slot outside the filter range

        ResponseEntity<SlotResponse[]> res = doQuery(userId, new SlotQueryFilter(null, T0, T1));

        assertThat(res.getBody()).hasSize(1);
        assertThat(res.getBody()[0].startTime()).isEqualTo(T0);
    }

    /**
     * The core QUERY gotcha this project exists to demonstrate: some clients don't
     * send a body at all for a non-standard method. A genuinely empty body must
     * still be treated as "no filter", not rejected — see SlotHandler.parseFilter.
     */
    @Test
    void querySlots_withNoBody_treatedAsNoFilter_returnsAllSlots() {
        ResponseEntity<SlotResponse[]> res = restTemplate.exchange(
                "/api/users/{uid}/slots", QUERY,
                new HttpEntity<>(jsonHeaders()),
                SlotResponse[].class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).hasSize(1);
    }

    /**
     * A *present but malformed* body must 400, not be silently swallowed into "no filter" —
     * only a genuinely empty/absent body gets that treatment. Regression test for a bug where
     * SlotHandler.parseFilter caught every exception, including a real parse failure, and
     * returned all slots unfiltered with no indication anything was wrong with the request.
     */
    @Test
    void querySlots_withMalformedJson_returns400_notAllSlots() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/users/{uid}/slots", QUERY,
                new HttpEntity<>("{not valid json", jsonHeaders()),
                String.class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void querySlots_withUnparseableFromDate_returns400_notAllSlots() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/users/{uid}/slots", QUERY,
                new HttpEntity<>("{\"from\":\"not-a-date\"}", jsonHeaders()),
                String.class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void querySlots_withInvalidStatusEnum_returns400_notAllSlots() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/users/{uid}/slots", QUERY,
                new HttpEntity<>("{\"status\":\"NOT_A_STATUS\"}", jsonHeaders()),
                String.class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── GET (single) ──────────────────────────────────────────────────────────

    @Test
    void getSlot_returns200_whenFound() {
        ResponseEntity<SlotResponse> res = restTemplate.getForEntity(
                "/api/users/{uid}/slots/{sid}", SlotResponse.class, userId, slotId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().id()).isEqualTo(slotId);
    }

    @Test
    void getSlot_returns404_whenNotFound() {
        ResponseEntity<String> res = restTemplate.getForEntity(
                "/api/users/{uid}/slots/9999", String.class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getSlot_returns400_whenUserIdNotNumeric() {
        ResponseEntity<String> res = restTemplate.getForEntity(
                "/api/users/{uid}/slots/{sid}", String.class, "not-a-number", slotId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getSlot_returns400_whenSlotIdNotNumeric() {
        ResponseEntity<String> res = restTemplate.getForEntity(
                "/api/users/{uid}/slots/{sid}", String.class, userId, "not-a-number");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── POST (bulk create) ────────────────────────────────────────────────────

    @Test
    void createSlots_returns201_andAllSlotsAreFree() {
        ResponseEntity<SlotResponse[]> res = restTemplate.postForEntity(
                "/api/users/{uid}/slots", new SlotBulkCreateRequest(List.of(T2, T3)), SlotResponse[].class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).hasSize(2);
        assertThat(res.getBody()).allMatch(s -> s.status() == SlotStatus.FREE);
        assertThat(res.getBody()[0].endTime()).isEqualTo(T3);
        assertThat(res.getBody()[1].endTime()).isEqualTo(T3.plus(30, ChronoUnit.MINUTES));
    }

    @Test
    void createSlots_returns409_whenOverlappingSlotExists() {
        // T0-T1 already seeded; requesting the exact same startTime is a conflict
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/users/{uid}/slots", new SlotBulkCreateRequest(List.of(T0)), String.class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createSlots_returns409_andCreatesNothing_whenOneOfManyOverlaps() {
        // T2 is free, T0 conflicts — the whole batch must fail, not just T0
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/users/{uid}/slots", new SlotBulkCreateRequest(List.of(T2, T0)), String.class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<SlotResponse[]> after = restTemplate.getForEntity(
                "/api/users/{uid}/slots", SlotResponse[].class, userId);
        assertThat(after.getBody()).hasSize(1); // only the originally-seeded slot
    }

    @Test
    void createSlots_returns400_whenStartTimeNotGridAligned() {
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/users/{uid}/slots", new SlotBulkCreateRequest(List.of(T2.plusSeconds(60))), String.class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlots_returns400_whenStartTimesEmpty() {
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/users/{uid}/slots", new SlotBulkCreateRequest(List.of()), String.class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlots_returns400_whenUserIdNotNumeric() {
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/users/{uid}/slots", new SlotBulkCreateRequest(List.of(T2)), String.class, "not-a-number");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Regression test for the TOCTOU race SlotService.create() used to have: the
     * overlap check and the insert weren't atomic, so two concurrent requests for
     * the same window could both pass the check before either committed. Fires N
     * concurrent requests for the exact same window and asserts the lock in
     * CalendarRepository.findByOwnerIdForUpdate serializes them down to exactly
     * one winner.
     */
    @Test
    void createSlots_concurrentOverlappingRequests_onlyOneSucceeds() throws Exception {
        var req = new SlotBulkCreateRequest(List.of(T2));
        int threadCount = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<HttpStatusCode>> futures = IntStream.range(0, threadCount)
                .<Future<HttpStatusCode>>mapToObj(i -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return restTemplate.postForEntity(
                            "/api/users/{uid}/slots", req, String.class, userId).getStatusCode();
                }))
                .toList();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<HttpStatusCode> statuses = new ArrayList<>();
        for (Future<HttpStatusCode> f : futures) {
            statuses.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == HttpStatus.CONFLICT).hasSize(threadCount - 1);
    }

    // ── PATCH (update) ────────────────────────────────────────────────────────

    @Test
    void patchSlot_marksAsBusy() {
        HttpHeaders headers = jsonHeaders();
        var req = new HttpEntity<>(new SlotUpdateRequest(null, SlotStatus.BUSY), headers);

        ResponseEntity<SlotResponse> res = restTemplate.exchange(
                "/api/users/{uid}/slots/{sid}", HttpMethod.PATCH, req, SlotResponse.class, userId, slotId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().status()).isEqualTo(SlotStatus.BUSY);
    }

    @Test
    void patchSlot_reschedule_recomputesEndTimeFromSystemDuration() {
        HttpHeaders headers = jsonHeaders();
        var req = new HttpEntity<>(new SlotUpdateRequest(T2, null), headers);

        ResponseEntity<SlotResponse> res = restTemplate.exchange(
                "/api/users/{uid}/slots/{sid}", HttpMethod.PATCH, req, SlotResponse.class, userId, slotId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().startTime()).isEqualTo(T2);
        assertThat(res.getBody().endTime()).isEqualTo(T3);
    }

    @Test
    void patchSlot_returns400_whenRescheduleNotGridAligned() {
        HttpHeaders headers = jsonHeaders();
        var req = new HttpEntity<>(new SlotUpdateRequest(T2.plusSeconds(60), null), headers);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/users/{uid}/slots/{sid}", HttpMethod.PATCH, req, String.class, userId, slotId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void patchSlot_returns400_whenStatusIsNotAValidEnumValue() {
        HttpHeaders headers = jsonHeaders();
        var req = new HttpEntity<>("{\"status\":\"NOT_A_STATUS\"}", headers);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/users/{uid}/slots/{sid}", HttpMethod.PATCH, req, String.class, userId, slotId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void patchSlot_returns400_whenBodyIsMalformedJson() {
        HttpHeaders headers = jsonHeaders();
        var req = new HttpEntity<>("{not valid json", headers);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/users/{uid}/slots/{sid}", HttpMethod.PATCH, req, String.class, userId, slotId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void patchSlot_returns409_whenRescheduleOverlapsAnotherSlot() {
        TestSupport.seedSlot(slotRepository, calendarRepository, userId, T2, T3);

        HttpHeaders headers = jsonHeaders();
        var req = new HttpEntity<>(new SlotUpdateRequest(T2, null), headers);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/users/{uid}/slots/{sid}", HttpMethod.PATCH, req, String.class, userId, slotId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Test
    void deleteSlot_returns204_andSlotIsGone() {
        restTemplate.delete("/api/users/{uid}/slots/{sid}", userId, slotId);

        ResponseEntity<String> check = restTemplate.getForEntity(
                "/api/users/{uid}/slots/{sid}", String.class, userId, slotId);
        assertThat(check.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<SlotResponse[]> doQuery(Long uid, SlotQueryFilter filter) {
        HttpHeaders headers = jsonHeaders();
        return restTemplate.exchange(
                "/api/users/{uid}/slots", QUERY,
                new HttpEntity<>(filter, headers),
                SlotResponse[].class, uid);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return h;
    }
}
