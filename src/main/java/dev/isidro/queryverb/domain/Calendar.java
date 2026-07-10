package dev.isidro.queryverb.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Domain-only concept: never exposed as a REST resource.
 * Owned by exactly one User (1-1); owns many Slots (1-N).
 */
@Entity
@Table(name = "calendar")
@Getter
@NoArgsConstructor
public class Calendar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "owner_id", nullable = false, unique = true)
    private User owner;

    @OneToMany(mappedBy = "calendar", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<Slot> slots = new ArrayList<>();

    public Calendar(User owner) {
        this.owner = owner;
        owner.assignCalendar(this);
    }

    public void addSlot(Slot slot) {
        slots.add(slot);
        slot.assignCalendar(this);
    }

}
