package dev.isidro.queryverb.repository;

import dev.isidro.queryverb.domain.Meeting;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    /**
     * Overrides the inherited findById to eagerly fetch slots: open-in-view is
     * disabled, so MeetingMapper reading meeting.getSlots() after the service's
     * @Transactional method returns would otherwise hit a closed session.
     *
     * <p>slots.calendar and slots.calendar.owner must be listed explicitly too:
     * an @EntityGraph with attributePaths applies as a JPA "fetch graph", which
     * demotes any association NOT in the graph to LAZY regardless of its mapping's
     * own default (Slot.calendar and Calendar.owner are both @ManyToOne/@OneToOne
     * EAGER by default) — SlotMapper needs both to resolve a slot's owning userId.
     */
    @Override
    @EntityGraph(attributePaths = {"slots", "slots.calendar", "slots.calendar.owner"})
    Optional<Meeting> findById(Long id);
}
