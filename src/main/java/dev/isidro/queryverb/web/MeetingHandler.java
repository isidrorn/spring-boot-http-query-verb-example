package dev.isidro.queryverb.web;

import dev.isidro.queryverb.service.MeetingService;
import dev.isidro.queryverb.web.dto.MeetingCreateRequest;
import dev.isidro.queryverb.web.mapper.MeetingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Component
@RequiredArgsConstructor
public class MeetingHandler {

    private final MeetingService meetingService;
    private final MeetingMapper meetingMapper;
    private final RequestValidator requestValidator;

    public ServerResponse schedule(ServerRequest request) throws Exception {
        Long userId = Long.valueOf(request.pathVariable("userId"));
        Long slotId = Long.valueOf(request.pathVariable("slotId"));
        var body = requestValidator.parseAndValidate(request, MeetingCreateRequest.class);
        var meeting = meetingService.schedule(userId, slotId, body);
        return ServerResponse.status(201).contentType(MediaType.APPLICATION_JSON)
                .body(meetingMapper.toResponse(meeting));
    }

    public ServerResponse getOne(ServerRequest request) throws Exception {
        Long meetingId = Long.valueOf(request.pathVariable("meetingId"));
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                .body(meetingMapper.toResponse(meetingService.findById(meetingId)));
    }

    public ServerResponse cancel(ServerRequest request) throws Exception {
        Long userId = Long.valueOf(request.pathVariable("userId"));
        Long slotId = Long.valueOf(request.pathVariable("slotId"));
        meetingService.cancel(userId, slotId);
        return ServerResponse.noContent().build();
    }
}
