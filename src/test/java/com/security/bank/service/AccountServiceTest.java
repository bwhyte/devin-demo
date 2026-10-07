package com.security.bank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.security.bank.dto.AccountDto;
import com.security.bank.dto.KycDto;
import com.security.bank.dto.NomineeDto;
import com.security.bank.entity.Account;
import com.security.bank.entity.AccountType;
import com.security.bank.entity.BranchType;
import com.security.bank.entity.Card;
import com.security.bank.entity.CardType;
import com.security.bank.entity.Investment;
import com.security.bank.entity.Nominee;
import com.security.bank.entity.User;
import com.security.bank.repository.AccountRepository;
import com.security.bank.repository.CardRepository;
import com.security.bank.repository.NomineeRepository;
import com.security.bank.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    AccountRepository accountRepository;
    @Mock
    NomineeRepository nomineeRepository;
    @Mock
    CardRepository cardRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    CardService cardService;
    @InjectMocks
    AccountService service;

    private User user;

    @BeforeEach
    void setUp() {
	user = new User();
	user.setId(7L);
	user.setName("Alice");
    }

    private static AccountDto dto(String type) {
	AccountDto dto = new AccountDto();
	dto.setAccountType(type);
	dto.setBalance(5000);
	dto.setProof("PAN");
	Nominee nominee = new Nominee();
	nominee.setName("Jane");
	dto.setNominee(nominee);
	return dto;
    }

    private void givenExistingUserAndGeneratedCard(String cardNumber) {
	when(userRepository.existsById(7L)).thenReturn(true);
	when(userRepository.findById(7L)).thenReturn(Optional.of(user));
	when(cardService.generateCardNumber()).thenReturn(cardNumber);
	when(cardService.generateCvv()).thenReturn(321);
    }

    private Account savedAccount() {
	ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
	verify(accountRepository).save(captor.capture());
	return captor.getValue();
    }

    @ParameterizedTest
    @CsvSource({ "SAVINGS, 2.70, BOB, DEBIT_GLOBAL, 40000", "CURRENT, 5.2, ICIC, CREDIT_PREMIUM, 50000",
	    "SALARY, 4.1, HDFC, CREDIT_MASTER, 75000" })
    void createAccountAppliesTypeDefaultsAndSavesACard(String type, float rate, BranchType branch, CardType cardType,
	    double limit) {
	givenExistingUserAndGeneratedCard("1234567890123456");
	AccountDto dto = dto(type);
	when(nomineeRepository.save(dto.getNominee())).thenReturn(dto.getNominee());

	service.createAccount(dto, 7L);

	Account account = savedAccount();
	assertThat(account.getAccountType()).isEqualTo(AccountType.valueOf(type));
	assertThat(account.getInterestRate()).isEqualTo(rate);
	assertThat(account.getBranch()).isEqualTo(branch);
	assertThat(account.getStatus()).isEqualTo("ACTIVE");
	assertThat(account.getBalance()).isEqualTo(5000);
	assertThat(account.getProof()).isEqualTo("PAN");
	assertThat(account.getUser()).isSameAs(user);
	assertThat(account.getNominee()).isSameAs(dto.getNominee());
	assertThat(account.getOpeningDate()).isNotNull();
	assertThat(account.getAccountNumber()).isBetween(10_000_000L, 99_999_999L);

	Card card = account.getCard();
	verify(cardRepository).save(card);
	assertThat(card.getCardType()).isEqualTo(cardType);
	assertThat(card.getDailyLimit()).isEqualTo(limit);
	assertThat(card.getCardNumber()).isEqualTo(1234567890123456L);
	assertThat(card.getCvv()).isEqualTo(321);
	assertThat(card.getPin()).isEqualTo(1122L);
	assertThat(card.getCardHolderName()).isEqualTo("Alice");
	assertThat(card.getStatus()).isEqualTo("ACTIVE");
	assertThat(Duration.between(card.getAllocationDate().toInstant(), card.getExpiryDate().toInstant()).toDays())
		.isBetween(1825L, 1827L);
    }

    @Test
    void createPpfAccountGeneratesButNeverSavesOrAttachesACard() {
	givenExistingUserAndGeneratedCard("1234567890123456");

	service.createAccount(dto("PPF"), 7L);

	Account account = savedAccount();
	assertThat(account.getAccountType()).isEqualTo(AccountType.PPF);
	assertThat(account.getBranch()).isEqualTo(BranchType.SBI);
	assertThat(account.getInterestRate()).isEqualTo(7.4F);
	assertThat(account.getCard()).isNull();
	verify(cardService).generateCardNumber();
	verify(cardRepository, never()).save(any());
    }

    @Test
    void createAccountDropsLeadingZeroesOfTheGeneratedCardNumber() {
	givenExistingUserAndGeneratedCard("0012345678901234");

	service.createAccount(dto("SAVINGS"), 7L);

	assertThat(savedAccount().getCard().getCardNumber()).isEqualTo(12345678901234L);
    }

    @Test
    void createAccountWithUnknownTypeThrowsBeforeSavingAnything() {
	givenExistingUserAndGeneratedCard("1234567890123456");

	assertThatThrownBy(() -> service.createAccount(dto("FIXED"), 7L)).isExactlyInstanceOf(RuntimeException.class)
		.hasMessage("No AccountType Selected");
	verifyNoInteractions(accountRepository, nomineeRepository, cardRepository);
    }

    @Test
    void createAccountWithNullTypeThrowsNullPointerException() {
	givenExistingUserAndGeneratedCard("1234567890123456");

	assertThatThrownBy(() -> service.createAccount(dto(null), 7L)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void createAccountForUnknownUserUsesATransientEmptyUser() {
	when(userRepository.existsById(99L)).thenReturn(false);
	when(cardService.generateCardNumber()).thenReturn("1234567890123456");

	service.createAccount(dto("SAVINGS"), 99L);

	Account account = savedAccount();
	verify(userRepository, never()).findById(any());
	assertThat(account.getUser()).isNotNull();
	assertThat(account.getUser().getId()).isNull();
	assertThat(account.getCard().getCardHolderName()).isNull();
    }

    @Test
    void generateRandomNumberIsAlwaysEightDigits() {
	for (int i = 0; i < 1000; i++) {
	    assertThat(service.generateRandomNumber()).isBetween(10_000_000L, 99_999_999L);
	}
    }

    @Test
    void getAllAccountByIdReturnsTheUsersAccountListOrThrows() {
	List<Account> accounts = new ArrayList<>(List.of(new Account()));
	user.setAccountList(accounts);
	when(userRepository.findById(7L)).thenReturn(Optional.of(user));
	when(userRepository.findById(8L)).thenReturn(Optional.empty());

	assertThat(service.getAllAccountById(7L)).isSameAs(accounts);
	assertThatThrownBy(() -> service.getAllAccountById(8L)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void balanceAndNomineeAreReadFromTheAccount() {
	Account account = new Account();
	account.setBalance(42.5);
	Nominee nominee = new Nominee();
	account.setNominee(nominee);
	when(accountRepository.findByAccountNumber(11L)).thenReturn(Optional.of(account));

	assertThat(service.getBalanceAmount(11L)).isEqualTo(42.5);
	assertThat(service.getNominee(11L)).isSameAs(nominee);
    }

    @Test
    void lookupsByUnknownAccountNumberThrowNoSuchElement() {
	when(accountRepository.findByAccountNumber(1L)).thenReturn(Optional.empty());

	assertThatThrownBy(() -> service.getBalanceAmount(1L)).isInstanceOf(NoSuchElementException.class);
	assertThatThrownBy(() -> service.getNominee(1L)).isInstanceOf(NoSuchElementException.class);
	assertThatThrownBy(() -> service.getAccountKycDetail(1L)).isInstanceOf(NoSuchElementException.class);
	assertThatThrownBy(() -> service.getAccountDetail(1L)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void kycDetailReturnsTheOwnerAfterClearingItsAccountAndInvestmentLists() {
	Account account = new Account();
	user.setAccountList(new ArrayList<>(List.of(account)));
	user.setInvestmentList(new ArrayList<>(List.of(new Investment())));
	account.setUser(user);
	when(accountRepository.findByAccountNumber(11L)).thenReturn(Optional.of(account));

	User result = service.getAccountKycDetail(11L);

	assertThat(result).isSameAs(user);
	assertThat(result.getAccountList()).isNull();
	assertThat(result.getInvestmentList()).isNull();
	verify(userRepository, never()).save(any());
    }

    @Test
    void accountDetailReturnsTheAccountAfterClearingItsUser() {
	Account account = new Account();
	account.setUser(user);
	when(accountRepository.findByAccountNumber(11L)).thenReturn(Optional.of(account));

	Account result = service.getAccountDetail(11L);

	assertThat(result).isSameAs(account);
	assertThat(result.getUser()).isNull();
	verify(accountRepository, never()).save(any());
    }

    @Test
    void updateNomineeOverwritesEveryFieldOfTheExistingNominee() {
	Account account = new Account();
	Nominee nominee = new Nominee();
	nominee.setId(3L);
	nominee.setName("Old");
	nominee.setRelation("OLD");
	account.setNominee(nominee);
	when(accountRepository.findById(5L)).thenReturn(Optional.of(account));
	NomineeDto dto = new NomineeDto();
	dto.setName("New");
	dto.setAccountNumber(99L);
	dto.setAge(30);
	dto.setGender("F");

	service.updateNominee(dto, 5L);

	assertThat(account.getNominee()).isSameAs(nominee);
	assertThat(nominee.getId()).isEqualTo(3L);
	assertThat(nominee.getName()).isEqualTo("New");
	assertThat(nominee.getRelation()).isNull();
	assertThat(nominee.getAccountNumber()).isEqualTo(99L);
	assertThat(nominee.getAge()).isEqualTo(30);
	assertThat(nominee.getGender()).isEqualTo("F");
	verify(accountRepository).save(account);
	verifyNoInteractions(nomineeRepository);
    }

    @Test
    void updateNomineeWithoutExistingNomineeThrowsNullPointerException() {
	when(accountRepository.findById(5L)).thenReturn(Optional.of(new Account()));

	assertThatThrownBy(() -> service.updateNominee(new NomineeDto(), 5L)).isInstanceOf(NullPointerException.class);
	verify(accountRepository, never()).save(any());
    }

    private Account accountOwnedByUser() {
	user.setAddress("1 Main St");
	user.setNumber(111L);
	user.setIdentityProof("PASSPORT");
	Account account = new Account();
	account.setUser(user);
	when(accountRepository.findById(5L)).thenReturn(Optional.of(account));
	return account;
    }

    private static KycDto kyc(String name, String address, Long number, String proof) {
	KycDto dto = new KycDto();
	dto.setName(name);
	dto.setAddress(address);
	dto.setNumber(number);
	dto.setIdentityProof(proof);
	return dto;
    }

    @Test
    void updateKycAppliesAllProvidedFields() {
	accountOwnedByUser();

	service.updateKycDetails(kyc("Bob", "2 High St", 222L, "DL"), 5L);

	assertThat(user.getName()).isEqualTo("Bob");
	assertThat(user.getAddress()).isEqualTo("2 High St");
	assertThat(user.getNumber()).isEqualTo(222L);
	assertThat(user.getIdentityProof()).isEqualTo("DL");
	verify(userRepository).save(user);
    }

    @Test
    void updateKycSkipsEmptyStringsAndNullNumber() {
	accountOwnedByUser();

	service.updateKycDetails(kyc("", "", null, ""), 5L);

	assertThat(user.getName()).isEqualTo("Alice");
	assertThat(user.getAddress()).isEqualTo("1 Main St");
	assertThat(user.getNumber()).isEqualTo(111L);
	assertThat(user.getIdentityProof()).isEqualTo("PASSPORT");
	verify(userRepository).save(user);
    }

    @Test
    void updateKycWithNullStringFieldThrowsNullPointerExceptionWithoutSaving() {
	accountOwnedByUser();

	assertThatThrownBy(() -> service.updateKycDetails(kyc("Bob", null, null, "DL"), 5L))
		.isInstanceOf(NullPointerException.class);
	assertThat(user.getName()).isEqualTo("Bob");
	verify(userRepository, never()).save(any());
    }
}
