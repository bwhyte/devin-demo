package com.security.bank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.security.bank.dto.AdminDto;
import com.security.bank.dto.UserDto;
import com.security.bank.entity.Account;
import com.security.bank.entity.AccountType;
import com.security.bank.entity.BranchType;
import com.security.bank.entity.User;
import com.security.bank.repository.AccountRepository;
import com.security.bank.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    AccountRepository accountRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @InjectMocks
    UserService service;

    private User savedUser() {
	ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
	verify(userRepository).save(captor.capture());
	return captor.getValue();
    }

    @Test
    void registerUserStoresBcryptHashAndCustomerRoleWithoutUsingTheInjectedEncoder() {
	UserDto dto = new UserDto();
	dto.setName("Alice");
	dto.setUsername("alice");
	dto.setPassword("pw");
	dto.setAddress("1 Main St");
	dto.setNumber(555L);
	dto.setIdentityProof("PASSPORT");
	dto.getAccountList().add(new Account());

	service.registerUser(dto);

	User user = savedUser();
	assertThat(user.getId()).isNull();
	assertThat(user.getName()).isEqualTo("Alice");
	assertThat(user.getUsername()).isEqualTo("alice");
	assertThat(user.getAddress()).isEqualTo("1 Main St");
	assertThat(user.getNumber()).isEqualTo(555L);
	assertThat(user.getIdentityProof()).isEqualTo("PASSPORT");
	assertThat(user.getPassword()).startsWith("$2a$10$");
	assertThat(new BCryptPasswordEncoder().matches("pw", user.getPassword())).isTrue();
	assertThat(user.getRoles().getId()).isNull();
	assertThat(user.getRoles().getRoleName()).isEqualTo("ROLE_CUSTOMER");
	assertThat(user.getAccountList()).isEmpty();
	verifyNoInteractions(passwordEncoder);
    }

    @Test
    void registerAdminStoresBcryptHashAndAdminRole() {
	AdminDto dto = new AdminDto();
	dto.setName("Root");
	dto.setUsername("root");
	dto.setPassword("pw");

	service.registerAdmin(dto);

	User user = savedUser();
	assertThat(user.getUsername()).isEqualTo("root");
	assertThat(new BCryptPasswordEncoder().matches("pw", user.getPassword())).isTrue();
	assertThat(user.getRoles().getRoleName()).isEqualTo("ROLE_ADMIN");
	verifyNoInteractions(passwordEncoder);
    }

    @Test
    void registeringWithoutPasswordThrowsIllegalArgumentException() {
	assertThatThrownBy(() -> service.registerUser(new UserDto())).isInstanceOf(IllegalArgumentException.class);
	assertThatThrownBy(() -> service.registerAdmin(new AdminDto())).isInstanceOf(IllegalArgumentException.class);
	verify(userRepository, never()).save(any());
    }

    @Test
    void getAllUsersAndGetUserByNameDelegateToTheRepository() {
	List<User> users = List.of(new User());
	User alice = new User();
	when(userRepository.findAll()).thenReturn(users);
	when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
	when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

	assertThat(service.getAllUsers()).isSameAs(users);
	assertThat(service.getUserByName("alice")).isSameAs(alice);
	assertThatThrownBy(() -> service.getUserByName("nobody")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void deleteUserByIdReportsOutcomeAsText() {
	when(userRepository.existsById(1L)).thenReturn(true);
	when(userRepository.existsById(2L)).thenReturn(false);

	assertThat(service.deleteUserById(1L)).isEqualTo("Deleted Successfully");
	assertThat(service.deleteUserById(2L)).isEqualTo("Error in deletion");
	verify(userRepository).deleteById(1L);
	verify(userRepository, never()).deleteById(2L);
    }

    private Account givenUserWithAccount(boolean accountBelongsToUser, String status) {
	User user = new User();
	Account account = new Account();
	account.setId(9L);
	account.setStatus(status);
	user.setAccountList(new ArrayList<>(accountBelongsToUser ? List.of(account) : List.of()));
	when(userRepository.existsById(1L)).thenReturn(true);
	when(accountRepository.existsById(9L)).thenReturn(true);
	when(userRepository.findById(1L)).thenReturn(Optional.of(user));
	when(accountRepository.findById(9L)).thenReturn(Optional.of(account));
	return account;
    }

    @Test
    void deactivateMarksAnOwnedAccountInactive() {
	Account account = givenUserWithAccount(true, "ACTIVE");

	assertThat(service.deactivateUser(1L, 9L)).isEqualTo("Deactivated Account for User with id: 1");
	assertThat(account.getStatus()).isEqualTo("INACTIVE");
	verify(accountRepository).save(account);
    }

    @Test
    void deactivateReportsSuccessButDoesNothingForAnAccountTheUserDoesNotOwn() {
	Account account = givenUserWithAccount(false, "ACTIVE");

	assertThat(service.deactivateUser(1L, 9L)).isEqualTo("Deactivated Account for User with id: 1");
	assertThat(account.getStatus()).isEqualTo("ACTIVE");
	verify(accountRepository, never()).save(any());
    }

    @Test
    void deactivateAndActivateReturnErrorWhenUserOrAccountIsMissing() {
	when(userRepository.existsById(1L)).thenReturn(false);
	when(userRepository.existsById(2L)).thenReturn(true);
	when(accountRepository.existsById(9L)).thenReturn(false);

	assertThat(service.deactivateUser(1L, 9L)).isEqualTo("ERROR");
	assertThat(service.deactivateUser(2L, 9L)).isEqualTo("ERROR");
	assertThat(service.activateAccount(1L, 9L)).isEqualTo("ERROR");
	assertThat(service.activateAccount(2L, 9L)).isEqualTo("ERROR");
	verify(accountRepository, never()).save(any());
    }

    @Test
    void activateMarksAnOwnedInactiveAccountActive() {
	Account account = givenUserWithAccount(true, "INACTIVE");

	assertThat(service.activateAccount(1L, 9L)).isEqualTo("Activated Account for User with id: 1");
	assertThat(account.getStatus()).isEqualTo("ACTIVE");
	verify(accountRepository).save(account);
    }

    @Test
    void activateReturnsErrorForAlreadyActiveOrNotOwnedAccounts() {
	givenUserWithAccount(true, "ACTIVE");
	assertThat(service.activateAccount(1L, 9L)).isEqualTo("ERROR");

	givenUserWithAccount(false, "INACTIVE");
	assertThat(service.activateAccount(1L, 9L)).isEqualTo("ERROR");
	verify(accountRepository, never()).save(any());
    }

    @Test
    void activateWithNullStatusThrowsNullPointerException() {
	givenUserWithAccount(true, null);

	assertThatThrownBy(() -> service.activateAccount(1L, 9L)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void accountListQueriesDelegateToTheRepository() {
	List<Account> active = List.of(new Account());
	List<Account> inactive = List.of(new Account());
	List<Account> savings = List.of(new Account());
	List<Account> bob = List.of(new Account());
	when(accountRepository.findAllActiveAccounts()).thenReturn(active);
	when(accountRepository.findAllInActiveAccounts()).thenReturn(inactive);
	when(accountRepository.findAllByAccountType(AccountType.SAVINGS)).thenReturn(savings);
	when(accountRepository.findAllByBranch(BranchType.BOB)).thenReturn(bob);

	assertThat(service.getAllActiveAccountList()).isSameAs(active);
	assertThat(service.getAllInActiveAccountList()).isSameAs(inactive);
	assertThat(service.byAccType(AccountType.SAVINGS)).isSameAs(savings);
	assertThat(service.byBranchType(BranchType.BOB)).isSameAs(bob);
    }
}
