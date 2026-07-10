package dev.isidro.queryverb.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.isidro.queryverb.domain.Calendar;
import dev.isidro.queryverb.domain.Slot;
import dev.isidro.queryverb.domain.SlotStatus;
import dev.isidro.queryverb.domain.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class SlotRepositoryTest {

    @Autowired
    TestEntityManager em;
    @Autowired SlotRepository slotRepository;

    private Long userId;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        User user = new User("Test", "test@test.com");
        Calendar calendar = new Calendar(user);
        calendar.addSlot(new Slot(now, now.plus(1, ChronoUnit.HOURS)));                                   // FREE
        calendar.addSlot(new Slot(now.plus(2, ChronoUnit.HOURS), now.plus(3, ChronoUnit.HOURS)));         // FREE
        em.persistAndFlush(user);
        // manually mark second slot BUSY
        var busySlot = calendar.getSlots().get(1);
        busySlot.markBusy();
        em.persistAndFlush(busySlot);
        userId = user.getId();
    }

    @Test
    void searchWithNoFilterReturnsAll() {
        assertThat(slotRepository.search(userId, null, null, null)).hasSize(2);
    }

    @Test
    void searchFiltersByStatus() {
        var free = slotRepository.search(userId, SlotStatus.FREE, null, null);
        assertThat(free).hasSize(1);
        assertThat(free.getFirst().getStatus()).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void searchFiltersByTimeRange() {
        var result = slotRepository.search(userId, null, now, now.plus(1, ChronoUnit.HOURS));
        assertThat(result).hasSize(1);
    }

    @Test
    void detectsOverlapCorrectly() {
        assertThat(slotRepository.existsOverlap(userId, now.minus(30, ChronoUnit.MINUTES), now.plus(30, ChronoUnit.MINUTES), null))
                .isTrue();
        assertThat(slotRepository.existsOverlap(userId, now.plus(10, ChronoUnit.HOURS), now.plus(11, ChronoUnit.HOURS), null))
                .isFalse();
    }
}
