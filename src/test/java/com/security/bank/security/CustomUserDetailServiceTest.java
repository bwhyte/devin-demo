package com.security.bank.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.security.bank.entity.Role;
import com.security.bank.entity.User;
import com.security.bank.repository.UserRepository;

class CustomUserDetailServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CustomUserDetailService service = new CustomUserDetailService(userRepository);

    @Test
    void returnsTheUserEntityWithItsSingleRoleAsAuthority() {
	User user = new User();
	user.setUsername("alice");
	user.setRoles(new Role(1L, "ROLE_CUSTOMER"));
	when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

	assertThat(service.loadUserByUsername("alice")).isSameAs(user);
	assertThat(user.getAuthorities()).extracting("authority").containsExactly("ROLE_CUSTOMER");
	assertThat(user.isEnabled() && user.isAccountNonExpired() && user.isAccountNonLocked()
		&& user.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void unknownUserThrowsPlainRuntimeExceptionRatherThanUsernameNotFound() {
	when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

	assertThatThrownBy(() -> service.loadUserByUsername("nobody")).isExactlyInstanceOf(RuntimeException.class)
		.hasMessage("User Not Found").isNotInstanceOf(UsernameNotFoundException.class);
    }
}
