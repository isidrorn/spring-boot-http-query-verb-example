package io.irn.minidoodle.web;

import io.irn.minidoodle.service.SlotService;
import io.irn.minidoodle.web.dto.SlotBulkCreateRequest;
import io.irn.minidoodle.web.dto.SlotQueryFilter;
import io.irn.minidoodle.web.dto.SlotUpdateRequest;
import io.irn.minidoodle.web.mapper.SlotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class SlotHandler {

    private final SlotService slotService;
    private final SlotMapper slotMapper;
    private final RequestValidator requestValidator;
    private final ObjectMapper objectMapper;

    public ServerResponse getOne(ServerRequest request) throws Exception {
        return ok(slotMapper.toResponse(slotService.requireOwned(userId(request), slotId(request))));
    }

    public ServerResponse listAll(ServerRequest request) throws Exception {
        Long userId = userId(request);
        var body = slotService.query(userId, SlotQueryFilter.empty())
                .stream().map(slotMapper::toResponse).toList();
        return ok(body);
    }

    /**
     * HTTP QUERY: safe + idempotent read with a structured filter in the body.
     * Semantically equivalent to a GET with query params, but without URI length limits
     * and with a typed, self-documenting payload.
     */
    public ServerResponse query(ServerRequest request) throws Exception {
        Long userId = userId(request);
        SlotQueryFilter filter = parseFilter(request);
        var body = slotService.query(userId, filter)
                .stream().map(slotMapper::toResponse).toList();
        return ok(body);
    }

    /** Bulk-creates every requested slot in one transaction; see SlotService.create. */
    public ServerResponse create(ServerRequest request) throws Exception {
        Long userId = userId(request);
        var body = requestValidator.parseAndValidate(request, SlotBulkCreateRequest.class);
        var slots = slotService.create(userId, body)
                .stream().map(slotMapper::toResponse).toList();
        return ServerResponse.status(201).contentType(MediaType.APPLICATION_JSON).body(slots);
    }

    public ServerResponse update(ServerRequest request) throws Exception {
        Long userId  = userId(request);
        Long slotId  = slotId(request);
        var slot = slotService.update(userId, slotId, request.body(SlotUpdateRequest.class));
        return ok(slotMapper.toResponse(slot));
    }

    public ServerResponse delete(ServerRequest request) throws Exception {
        slotService.delete(userId(request), slotId(request));
        return ServerResponse.noContent().build();
    }

    private Long userId(ServerRequest req) {
        return requestValidator.parseId(req, "userId");
    }

    private Long slotId(ServerRequest req) {
        return requestValidator.parseId(req, "slotId");
    }

    /**
     * Content-Length is unreliable here: the QUERY verb isn't recognized by every
     * HTTP client as carrying a body, so some clients omit the header even when a
     * body is present. Read the raw body first and only treat a genuinely blank one as
     * "no filter" — a *present but malformed* body (bad JSON, an unparseable date, an
     * invalid status) must still 400 rather than be silently swallowed into "no filter",
     * which would otherwise return an unfiltered result set with no indication the
     * client's filter was ignored.
     */
    private SlotQueryFilter parseFilter(ServerRequest request) throws Exception {
        String raw = request.body(String.class);
        if (raw == null || raw.isBlank()) {
            return SlotQueryFilter.empty();
        }
        try {
            return objectMapper.readValue(raw, SlotQueryFilter.class);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed request body: " + rootCauseMessage(e));
        }
    }

    private static String rootCauseMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private ServerResponse ok(Object body) throws Exception {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
