package com.security.bank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.security.bank.dto.CardDto;
import com.security.bank.entity.Account;
import com.security.bank.entity.Card;
import com.security.bank.entity.CardType;
import com.security.bank.repository.AccountRepository;
import com.security.bank.repository.CardRepository;

@ExtendWith(MockitoExtension.class)
class CardServiceTest {

    @Mock
    AccountRepository accountRepository;
    @Mock
    CardRepository cardRepository;
    @InjectMocks
    CardService service;

    private static Card card(Long id, Long number, CardType type, double limit) {
	Card card = new Card();
	card.setId(id);
	card.setCardNumber(number);
	card.setCardType(type);
	card.setDailyLimit(limit);
	card.setPin(1122L);
	return card;
    }

    private Account accountWithCard(Card card) {
	Account account = new Account();
	account.setCard(card);
	when(accountRepository.findByAccountNumber(10L)).thenReturn(Optional.of(account));
	return account;
    }

    @Test
    void blockCardUnlinksAndDeletesAMatchingCard() {
	Account account = accountWithCard(card(5L, 111L, CardType.DEBIT_GLOBAL, 1));
	when(cardRepository.findByCardNumber(111L)).thenReturn(Optional.of(card(5L, 111L, CardType.DEBIT_GLOBAL, 1)));

	assertThat(service.blockCard(10L, 111L)).isEqualTo("Card Blocked Successfully");
	assertThat(account.getCard()).isNull();
	verify(accountRepository).save(account);
	verify(cardRepository).deleteById(5L);
    }

    @Test
    void blockCardWithAnotherCardNumberThrows() {
	accountWithCard(card(5L, 111L, CardType.DEBIT_GLOBAL, 1));
	when(cardRepository.findByCardNumber(222L)).thenReturn(Optional.of(card(6L, 222L, CardType.DEBIT_GLOBAL, 1)));

	assertThatThrownBy(() -> service.blockCard(10L, 222L)).isExactlyInstanceOf(RuntimeException.class)
		.hasMessage("No Card Found with the given cardNumber: 222");
	verify(accountRepository, never()).save(any());
	verify(cardRepository, never()).deleteById(anyLong());
    }

    @Test
    void blockCardOnAccountWithoutCardThrowsNullPointerException() {
	accountWithCard(null);
	when(cardRepository.findByCardNumber(222L)).thenReturn(Optional.of(card(6L, 222L, CardType.DEBIT_GLOBAL, 1)));

	assertThatThrownBy(() -> service.blockCard(10L, 222L)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void blockCardWithUnknownAccountOrCardThrowsNoSuchElement() {
	when(accountRepository.findByAccountNumber(1L)).thenReturn(Optional.empty());
	assertThatThrownBy(() -> service.blockCard(1L, 2L)).isInstanceOf(NoSuchElementException.class);

	accountWithCard(card(5L, 111L, CardType.DEBIT_GLOBAL, 1));
	when(cardRepository.findByCardNumber(2L)).thenReturn(Optional.empty());
	assertThatThrownBy(() -> service.blockCard(10L, 2L)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void applyNewCardRejectsAccountsThatAlreadyHaveACard() {
	accountWithCard(card(5L, 111L, CardType.DEBIT_GLOBAL, 1));

	assertThatThrownBy(() -> service.applyNewCard(10L, new CardDto())).isExactlyInstanceOf(RuntimeException.class)
		.hasMessage("Account with number: 10 already has a card.");
	verify(cardRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({ "DEBIT_CLASSIC, DEBIT_CLASSIC, 20000", "CREDIT_PREMIUM, CREDIT_PREMIUM, 50000",
	    "CREDIT_MASTER, CREDIT_MASTER, 75000", "DEBIT_GLOBAL, DEBIT_GLOBAL, 40000", "anything, DEBIT_GLOBAL, 40000" })
    void applyNewCardIssuesACardWithTypeDefaults(String requested, CardType issued, double limit) {
	Account account = accountWithCard(null);
	when(cardRepository.save(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));
	CardDto dto = new CardDto();
	dto.setCardType(requested);
	dto.setCardHolderName("Alice");
	dto.setPin(4321L);

	String result = service.applyNewCard(10L, dto);

	assertThat(result).isEqualTo("New Card Allocated to account wih Number: 10");
	Card card = account.getCard();
	assertThat(card.getCardType()).isEqualTo(issued);
	assertThat(card.getDailyLimit()).isEqualTo(limit);
	assertThat(card.getPin()).isEqualTo(4321L);
	assertThat(card.getCardHolderName()).isEqualTo("Alice");
	assertThat(card.getStatus()).isEqualTo("ACTIVE");
	assertThat(card.getCvv()).isBetween(100, 999);
	assertThat(card.getCardNumber()).isBetween(0L, 9_999_999_999_999_999L);
	assertThat(Duration.between(card.getAllocationDate().toInstant(), card.getExpiryDate().toInstant()).toDays())
		.isBetween(1825L, 1827L);
	verify(accountRepository).save(account);
    }

    @Test
    void applyNewCardIsAllowedWhenExistingCardHasNoNumber() {
	Account account = accountWithCard(card(5L, null, CardType.DEBIT_GLOBAL, 1));
	when(cardRepository.save(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));
	CardDto dto = new CardDto();
	dto.setCardType("DEBIT_CLASSIC");

	service.applyNewCard(10L, dto);

	assertThat(account.getCard().getCardNumber()).isNotNull();
	assertThat(account.getCard().getId()).isNull();
    }

    @Test
    void applyNewCardWithNullTypeThrowsNullPointerException() {
	accountWithCard(null);

	assertThatThrownBy(() -> service.applyNewCard(10L, new CardDto())).isInstanceOf(NullPointerException.class);
	verify(cardRepository, never()).save(any());
    }

    @Test
    void generatorsProduceThreeDigitCvvAndSixteenDigitStrings() {
	for (int i = 0; i < 1000; i++) {
	    assertThat(service.generateCvv()).isBetween(100, 999);
	    assertThat(service.generateCardNumber()).hasSize(16).containsOnlyDigits();
	}
    }

    @ParameterizedTest
    @CsvSource({ "DEBIT_GLOBAL, 50000, 50000", "DEBIT_GLOBAL, 50001, 1000", "DEBIT_CLASSIC, 40000, 40000",
	    "DEBIT_CLASSIC, 40001, 1000", "CREDIT_PREMIUM, 75000, 75000", "CREDIT_PREMIUM, 75001, 1000",
	    "CREDIT_MASTER, 100000, 100000", "CREDIT_MASTER, 100001, 1000", "DEBIT_GLOBAL, 0, 1000",
	    "DEBIT_GLOBAL, -5, -5" })
    void modifySettingAppliesLimitOnlyWhenNonZeroAndWithinTypeCeiling(CardType type, double requested,
	    double expected) {
	Card stored = card(5L, 111L, type, 1000);
	when(cardRepository.findByCardNumber(111L)).thenReturn(Optional.of(stored));
	Card request = new Card();
	request.setDailyLimit(requested);

	service.modifySetting(111L, request);

	assertThat(stored.getDailyLimit()).isEqualTo(expected);
	assertThat(stored.getPin()).isEqualTo(1122L);
	verify(cardRepository).save(stored);
    }

    @Test
    void modifySettingUpdatesPinWhenProvided() {
	Card stored = card(5L, 111L, CardType.DEBIT_GLOBAL, 1000);
	when(cardRepository.findByCardNumber(111L)).thenReturn(Optional.of(stored));
	Card request = new Card();
	request.setPin(9999L);

	service.modifySetting(111L, request);

	assertThat(stored.getPin()).isEqualTo(9999L);
	assertThat(stored.getDailyLimit()).isEqualTo(1000);
    }

    @Test
    void modifySettingFailsForUnknownCardOrCardWithoutType() {
	when(cardRepository.findByCardNumber(1L)).thenReturn(Optional.empty());
	assertThatThrownBy(() -> service.modifySetting(1L, new Card())).isInstanceOf(NoSuchElementException.class);

	when(cardRepository.findByCardNumber(111L)).thenReturn(Optional.of(card(5L, 111L, null, 1000)));
	assertThatThrownBy(() -> service.modifySetting(111L, new Card())).isInstanceOf(NullPointerException.class);
    }
}
