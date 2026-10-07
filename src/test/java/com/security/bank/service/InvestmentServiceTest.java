package com.security.bank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.security.bank.dto.InvestmentDto;
import com.security.bank.entity.Account;
import com.security.bank.entity.Investment;
import com.security.bank.entity.InvestmentType;
import com.security.bank.entity.User;
import com.security.bank.repository.AccountRepository;
import com.security.bank.repository.InvestmentRepository;

@ExtendWith(MockitoExtension.class)
class InvestmentServiceTest {

    @Mock
    AccountRepository accountRepository;
    @Mock
    InvestmentRepository investmentRepository;
    @InjectMocks
    InvestmentService service;

    private final User owner = new User();

    private Account accountWithBalance(double balance) {
	Account account = new Account();
	account.setBalance(balance);
	account.setUser(owner);
	when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
	return account;
    }

    private static InvestmentDto dto(String type, double amount) {
	InvestmentDto dto = new InvestmentDto();
	dto.setInvestmentType(type);
	dto.setAmount(amount);
	dto.setDuration("12 months");
	return dto;
    }

    @ParameterizedTest
    @CsvSource({ "GOLD, GOLD, Low, 12, BuyNow", "STOCKS, STOCKS, high, 20, StockWay",
	    "MUTUAL_FUND, MUTUAL_FUND, Moderate, 12.3, ray fund", "FIXED_DEPOSITS, FIXED_DEPOSITS, Low, 9.2, PST",
	    "other, FIXED_DEPOSITS, Low, 9.2, PST" })
    void investNowSavesInvestmentWithTypeDefaultsWithoutDebitingTheAccount(String requested, InvestmentType type,
	    String risk, float returns, String company) {
	Account account = accountWithBalance(5000);

	assertThat(service.investNow(1L, dto(requested, 1000))).isEqualTo("Investment successful");

	ArgumentCaptor<Investment> captor = ArgumentCaptor.forClass(Investment.class);
	verify(investmentRepository).save(captor.capture());
	Investment investment = captor.getValue();
	assertThat(investment.getInvestmentType()).isEqualTo(type);
	assertThat(investment.getRisk()).isEqualTo(risk);
	assertThat(investment.getReturns()).isEqualTo(returns);
	assertThat(investment.getCompanyName()).isEqualTo(company);
	assertThat(investment.getAmount()).isEqualTo(1000);
	assertThat(investment.getDuration()).isEqualTo("12 months");
	assertThat(investment.getUser()).isSameAs(owner);
	assertThat(account.getBalance()).isEqualTo(5000);
	verify(accountRepository, never()).save(any());
    }

    @Test
    void investNowRequiresBalanceStrictlyGreaterThanAmount() {
	accountWithBalance(1000);

	assertThatThrownBy(() -> service.investNow(1L, dto("GOLD", 1000))).isExactlyInstanceOf(RuntimeException.class)
		.hasMessage("Error in Investment");
	assertThatThrownBy(() -> service.investNow(1L, dto("GOLD", 1001))).hasMessage("Error in Investment");
	verify(investmentRepository, never()).save(any());
    }

    @Test
    void investNowWithNullTypeThrowsNullPointerException() {
	accountWithBalance(1000);

	assertThatThrownBy(() -> service.investNow(1L, dto(null, 1))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void investNowWithUnknownAccountThrowsNoSuchElement() {
	when(accountRepository.findById(2L)).thenReturn(Optional.empty());

	assertThatThrownBy(() -> service.investNow(2L, dto("GOLD", 1))).isInstanceOf(NoSuchElementException.class);
    }
}
