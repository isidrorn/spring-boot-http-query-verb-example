package dev.isidro.queryverb;

import dev.isidro.queryverb.domain.Calendar;
import dev.isidro.queryverb.domain.Slot;
import dev.isidro.queryverb.domain.User;
import dev.isidro.queryverb.repository.CalendarRepository;
import dev.isidro.queryverb.repository.MeetingRepository;
import dev.isidro.queryverb.repository.SlotRepository;
import dev.isidro.queryverb.repository.UserRepository;
import java.time.Instant;

/** Static test helpers shared across IT tests — no Spring context, no inheritance. */
public final class TestSupport {

    private TestSupport() {}

    /**
     * Deletes all data in FK-safe order.
     * slot.meeting_id → meeting, slot.calendar_id → calendar, calendar.owner_id → user.
     */
    public static void cleanUp(SlotRepository slots, MeetingRepository meetings,
                                CalendarRepository calendars, UserRepository users) {
        slots.deleteAll();
        meetings.deleteAll();
        calendars.deleteAll();
        users.deleteAll();
    }

    /**
     * Creates a User + Calendar and returns the userId.
     * Persists via userRepository so the User→Calendar cascade (CascadeType.ALL) fires correctly.
     */
    public static Long seedUser(UserRepository userRepo, CalendarRepository calRepo,
                                String name, String email) {
        User user = new User(name, email);
        Calendar calendar = new Calendar(user);   // sets user.calendar via assignCalendar()
        userRepo.save(user);                       // cascade ALL → saves Calendar too
        return user.getId();
    }

    /**
     * Adds a FREE slot to the user's calendar and returns the slotId.
     *
     * <p>Saves via slotRepository (not calRepo.save(cal)): the fetched Calendar
     * already has an id, so JpaRepository.save would merge() it into a detached
     * copy and leave our in-memory {@code slot}'s generated id null.
     */
    public static Long seedSlot(SlotRepository slotRepo, CalendarRepository calRepo,
                                 Long userId, Instant start, Instant end) {
        Calendar cal = calRepo.findByOwnerId(userId).orElseThrow();
        Slot slot = new Slot(start, end);
        cal.addSlot(slot);
        return slotRepo.save(slot).getId();
    }
}
