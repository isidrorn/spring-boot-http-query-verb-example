package dev.isidro.queryverb.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Slot duration is a system parameter, not something a user chooses per-slot: every slot
 * (created individually or in bulk) must be exactly this long and start on this grid.
 */
@Component
@ConfigurationProperties(prefix = "scheduling")
@Getter
@Setter
public class SlotDurationConfig {

    private int slotDurationMinutes = 30;
}
