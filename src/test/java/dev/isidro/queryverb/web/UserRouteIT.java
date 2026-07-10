package dev.isidro.queryverb.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.isidro.queryverb.TestSupport;
import dev.isidro.queryverb.repository.CalendarRepository;
import dev.isidro.queryverb.repository.MeetingRepository;
import dev.isidro.queryverb.repository.SlotRepository;
import dev.isidro.queryverb.repository.UserRepository;
import dev.isidro.queryverb.web.dto.UserCreateRequest;
import dev.isidro.queryverb.web.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class UserRouteIT {

    @Autowired TestRestTemplate    restTemplate;
    @Autowired SlotRepository      slotRepository;
    @Autowired MeetingRepository   meetingRepository;
    @Autowired CalendarRepository  calendarRepository;
    @Autowired UserRepository      userRepository;

    @BeforeEach
    void cleanUp() {
        TestSupport.cleanUp(slotRepository, meetingRepository, calendarRepository, userRepository);
    }

    @Test
    void listUsers_returnsEmptyList_whenNoUsers() {
        ResponseEntity<UserResponse[]> res = restTemplate.getForEntity("/api/users", UserResponse[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEmpty();
    }

    @Test
    void createUser_returns201_andCanBeRetrieved() {
        ResponseEntity<UserResponse> created = restTemplate.postForEntity(
                "/api/users", new UserCreateRequest("Alice", "alice@test.com"), UserResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long userId = created.getBody().id();

        ResponseEntity<UserResponse> fetched = restTemplate.getForEntity("/api/users/{id}", UserResponse.class, userId);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().email()).isEqualTo("alice@test.com");
    }

    @Test
    void getUser_returns404_whenNotFound() {
        assertThat(restTemplate.getForEntity("/api/users/9999", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listUsers_returnsAllSeeded() {
        TestSupport.seedUser(userRepository, calendarRepository, "Alice", "alice@test.com");
        TestSupport.seedUser(userRepository, calendarRepository, "Bob", "bob@test.com");

        assertThat(restTemplate.getForEntity("/api/users", UserResponse[].class).getBody()).hasSize(2);
    }
}
