package dev.isidro.queryverb.web.dto;

import dev.isidro.queryverb.domain.ParticipantRole;
import dev.isidro.queryverb.domain.Vote;

public record ParticipantResponse(
        Long userId,
        String name,
        ParticipantRole role,
        Vote vote
) {}
