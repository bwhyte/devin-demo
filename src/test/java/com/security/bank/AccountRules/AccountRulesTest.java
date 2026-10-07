package com.security.bank.AccountRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.security.bank.entity.Account;
import com.security.bank.entity.AccountType;
import com.security.bank.entity.BranchType;
import com.security.bank.entity.Card;
import com.security.bank.entity.CardType;

/** AccountRules is a Spring bean that nothing in the application calls today. */
class AccountRulesTest {

    private final AccountRules rules = new AccountRules();

    @ParameterizedTest
    @CsvSource({ "SAVINGS, 2.70, BOB, DEBIT_GLOBAL, 40000", "CURRENT, 5.2, ICIC, CREDIT_PREMIUM, 50000",
	    "SALARY, 4.1, HDFC, CREDIT_MASTER, 75000" })
    void applyDefaultsSetsRateBranchAndCard(AccountType type, float rate, BranchType branch, CardType cardType,
	    double limit) {
	Account account = new Account();

	rules.applyDefaultsForAccount(account, type);

	assertThat(account.getAccountType()).isEqualTo(type);
	assertThat(account.getInterestRate()).isEqualTo(rate);
	assertThat(account.getBranch()).isEqualTo(branch);
	assertThat(account.getCard().getCardType()).isEqualTo(cardType);
	assertThat(account.getCard().getDailyLimit()).isEqualTo(limit);
    }

    @Test
    void applyDefaultsForPpfAddsNoCard() {
	Account account = new Account();

	rules.applyDefaultsForAccount(account, AccountType.PPF);

	assertThat(account.getAccountType()).isEqualTo(AccountType.PPF);
	assertThat(account.getInterestRate()).isEqualTo(7.4F);
	assertThat(account.getBranch()).isEqualTo(BranchType.SBI);
	assertThat(account.getCard()).isNull();
    }

    @Test
    void applyDefaultsWithNullTypeThrowsNullPointerException() {
	assertThatThrownBy(() -> rules.applyDefaultsForAccount(new Account(), null))
		.isInstanceOf(NullPointerException.class);
    }

    @Test
    void ensureCardCreatesACardWhenMissing() {
	Account account = new Account();
	long before = System.currentTimeMillis();

	rules.ensureCard(account, CardType.DEBIT_CLASSIC, 123);

	Card card = account.getCard();
	assertThat(card.getCardType()).isEqualTo(CardType.DEBIT_CLASSIC);
	assertThat(card.getDailyLimit()).isEqualTo(123);
	assertThat(card.getCvv()).isBetween(100, 999);
	assertThat(card.getCardNumber()).isBetween(before, System.currentTimeMillis());
	assertThat(card.getStatus()).isEqualTo("ACTIVE");
	assertThat(card.getPin()).isNull();
	assertThat(Duration.between(card.getAllocationDate().toInstant(), card.getExpiryDate().toInstant()).toDays())
		.isBetween(1825L, 1827L);
    }

    @Test
    void ensureCardOverwritesAnExistingCardInPlace() {
	Account account = new Account();
	Card existing = new Card();
	existing.setId(4L);
	existing.setPin(1111L);
	existing.setStatus("BLOCKED");
	account.setCard(existing);

	rules.ensureCard(account, CardType.CREDIT_MASTER, 1);

	assertThat(account.getCard()).isSameAs(existing);
	assertThat(existing.getId()).isEqualTo(4L);
	assertThat(existing.getPin()).isEqualTo(1111L);
	assertThat(existing.getStatus()).isEqualTo("ACTIVE");
	assertThat(existing.getCardType()).isEqualTo(CardType.CREDIT_MASTER);
    }

    @ParameterizedTest
    @CsvSource({ "DEBIT_CLASSIC, 40000", "DEBIT_GLOBAL, 50000", "CREDIT_PREMIUM, 75000", "CREDIT_MASTER, 100000" })
    void maxLimitForEachCardType(CardType type, double limit) {
	assertThat(rules.maxLimitFor(type)).isEqualTo(limit);
    }
}
