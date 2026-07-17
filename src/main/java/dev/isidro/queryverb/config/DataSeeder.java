package dev.isidro.queryverb.config;

import dev.isidro.queryverb.domain.Calendar;
import dev.isidro.queryverb.domain.Slot;
import dev.isidro.queryverb.domain.User;
import dev.isidro.queryverb.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private static final int SEED_SLOTS_PER_USER = 4;

    private final UserRepository userRepository;
    private final SlotDurationConfig slotDurationConfig;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Same grid start for both users, so their slots share a window a demo meeting can book.
        Instant gridStart = Instant.now().truncatedTo(ChronoUnit.HOURS);
        seedUser("Alice", "alice@example.dev", gridStart);
        seedUser("Bob", "bob@example.dev", gridStart);
        log.info("Seed complete. Use the logged calendar IDs to call the API.");
    }

    private void seedUser(String name, String email, Instant gridStart) {
        User user = new User(name, email);
        Calendar calendar = new Calendar(user);

        long durationMinutes = slotDurationConfig.getSlotDurationMinutes();
        for (int i = 0; i < SEED_SLOTS_PER_USER; i++) {
            Instant start = gridStart.plus(i * durationMinutes, ChronoUnit.MINUTES);
            calendar.addSlot(new Slot(start, start.plus(durationMinutes, ChronoUnit.MINUTES)));
        }
        Instant nextDayStart = gridStart.plus(1, ChronoUnit.DAYS);
        calendar.addSlot(new Slot(nextDayStart, nextDayStart.plus(durationMinutes, ChronoUnit.MINUTES)));

        userRepository.save(user);
        log.info("Seeded user='{}' userId={} calendarId={}", name, user.getId(), calendar.getId());
    }
}
