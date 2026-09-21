package com.orinan.api.domain.user;

import com.orinan.api.domain.token.business.TokenBusiness;
import com.orinan.api.domain.user.business.UserBusiness;
import com.orinan.api.domain.user.controller.UserApiController;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.api.domain.userprofile.business.UserProfileBusiness;
import com.orinan.api.exceptionhandler.ValidExceptionHandler;
import com.orinan.db.user.UserRepository;
import com.orinan.db.user.enums.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserExistsApiTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final UserProfileBusiness profileBusiness = mock(UserProfileBusiness.class);
    private final UserBusiness business = new UserBusiness(new UserService(repository, passwordEncoder),
            mock(UserConverter.class), profileBusiness, passwordEncoder, mock(TokenBusiness.class));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserApiController(profileBusiness, business))
            .setControllerAdvice(new ValidExceptionHandler()).build();

    @Test
    void registeredEmailReturnsOnlyTrueWithoutUserDetails() throws Exception {
        when(repository.existsByEmailIgnoreCaseAndStatus("MEMBER@example.com", UserStatus.REGISTERED)).thenReturn(true);

        mvc.perform(post("/api/users/exists").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"MEMBER@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(true))
                .andExpect(jsonPath("$.body.id").doesNotExist())
                .andExpect(jsonPath("$.body.email").doesNotExist());
        verify(repository).existsByEmailIgnoreCaseAndStatus("MEMBER@example.com", UserStatus.REGISTERED);
    }

    @Test
    void missingOrUnregisteredEmailReturnsFalse() throws Exception {
        when(repository.existsByEmailIgnoreCaseAndStatus("missing@example.com", UserStatus.REGISTERED)).thenReturn(false);

        mvc.perform(post("/api/users/exists").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"missing@example.com"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body").value(false));
        verify(repository).existsByEmailIgnoreCaseAndStatus("missing@example.com", UserStatus.REGISTERED);
    }

    @Test
    void invalidEmailIsRejectedBeforeLookup() throws Exception {
        mvc.perform(post("/api/users/exists").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }

    @Test
    void missingEmailIsRejectedBeforeLookup() throws Exception {
        mvc.perform(post("/api/users/exists").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
}
