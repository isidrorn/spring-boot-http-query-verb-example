package dev.isidro.queryverb.web.dto;

import dev.isidro.queryverb.domain.Vote;
import jakarta.validation.constraints.NotNull;

public record VoteRequest(
        @NotNull Vote vote
) {}
