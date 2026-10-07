package com.security.bank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import com.security.bank.dto.JwtRequest;
import com.security.bank.dto.JwtResponse;
import com.security.bank.jwt.JwtAuthenticationHelper;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    AuthenticationManager manager;
    @Mock
    JwtAuthenticationHelper jwtHelper;
    @Mock
    UserDetailsService userDetailsService;
    @InjectMocks
    AuthService service;

    @Test
    void loginAuthenticatesThenReturnsATokenForTheLoadedUser() {
	UserDetails alice = User.withUsername("alice").password("x").authorities("ROLE_CUSTOMER").build();
	when(userDetailsService.loadUserByUsername("alice")).thenReturn(alice);
	when(jwtHelper.generateToken(alice)).thenReturn("token-123");

	JwtResponse response = service.login(new JwtRequest("alice", "pw"));

	assertThat(response.getJwtToken()).isEqualTo("token-123");
	ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
	verify(manager).authenticate(captor.capture());
	assertThat(captor.getValue()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
	assertThat(captor.getValue().getPrincipal()).isEqualTo("alice");
	assertThat(captor.getValue().getCredentials()).isEqualTo("pw");
    }

    @Test
    void badCredentialsAreRethrownWithAGenericMessage() {
	when(manager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

	assertThatThrownBy(() -> service.login(new JwtRequest("alice", "wrong")))
		.isExactlyInstanceOf(BadCredentialsException.class).hasMessage("Invalid Username or Password")
		.hasNoCause();
	verifyNoInteractions(userDetailsService, jwtHelper);
    }

    @Test
    void otherAuthenticationFailuresPropagateUnchanged() {
	DisabledException disabled = new DisabledException("disabled");
	when(manager.authenticate(any())).thenThrow(disabled);

	assertThatThrownBy(() -> service.login(new JwtRequest("alice", "pw"))).isSameAs(disabled);
	verifyNoInteractions(userDetailsService, jwtHelper);
    }
}
