package dev.isidro.queryverb.repository;

import dev.isidro.queryverb.domain.Calendar;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarRepository extends JpaRepository<Calendar, Long> {

    /**
     * Eagerly fetches slots so callers can mutate the collection (e.g. addSlot)
     * even outside an active persistence context, without a LazyInitializationException.
     */
    @EntityGraph(attributePaths = "slots")
    Optional<Calendar> findByOwnerId(Long ownerId);

    /**
     * Locks the calendar row for the duration of the caller's transaction, without
     * loading its slots collection. Used by SlotService.create/update to serialize
     * the overlap-check-then-write sequence per user — otherwise two concurrent
     * requests can both pass existsOverlap() before either commits, since a fresh
     * INSERT has no existing row for a plain row-level lock to catch.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Calendar c where c.owner.id = :ownerId")
    Optional<Calendar> findByOwnerIdForUpdate(@Param("ownerId") Long ownerId);
}
