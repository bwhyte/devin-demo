# Bank Security Application

Spring Boot banking API for managing customer registration, JWT login, accounts, cards, KYC details, nominees, and investments.

## Overview

This application demonstrates a layered banking workflow with stateless JWT authentication, role-based access control, and JPA-backed persistence. Customers can register, log in, open accounts, manage cards and nominees, and invest from eligible balances. Admins can register as admins, inspect users, and manage account activation.

## Concepts and Features Covered

- Spring Boot REST API setup
- Spring Data JPA repository pattern
- MySQL-backed persistence
- Spring Security with JWT authentication
- Role-based access control with `@PreAuthorize`
- Customer and admin registration flows
- Account opening with account-type specific logic
- Card application, blocking, and limit updates
- KYC and nominee updates
- Investment creation from customer balances
- Custom JWT login endpoint

## Tech Stack

- Java 17
- Spring Boot 2.7
- Spring Web
- Spring Data JPA
- Spring Security
- Spring Validation
- MySQL
- Maven
- Lombok
- JJWT

## Project Structure

```text
Bank Security Application/
├── README.md
├── CHANGELOG.md
├── pom.xml
├── mvnw
├── mvnw.cmd
└── src/
    └── main/
        ├── java/com/security/bank/
        │   ├── AccountRules/
        │   ├── accounts/
        │   ├── admin/
        │   ├── cards/
        │   ├── config/
        │   ├── dto/
        │   ├── entity/
        │   ├── jwt/
        │   ├── repository/
        │   ├── security/
        │   ├── service/
        │   ├── user/
        │   └── BankApplication.java
        └── resources/
            └── application.yml
```

## How to Run

1. Open a terminal in the project root.
2. Update MySQL and JWT settings in `src/main/resources/application.yml` if needed.
3. Run `./mvnw verify` to run the tests and write a JaCoCo line-coverage report to `target/site/jacoco/index.html`. Tests use the in-memory H2 `test` profile, so MySQL isn't needed. The build fails if line coverage drops below 95%.
4. Run `./mvnw spring-boot:run` (against MySQL).
5. Register a user with `POST /user/register`.
6. Log in with `POST /user/login`.
7. Call protected endpoints with `Authorization: Bearer <token>`.

To run the app without MySQL, use the H2 `test` profile from `src/test/resources/application-test.yml`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=test \
  -Dspring-boot.run.useTestClasspath=true \
  -Dspring-boot.run.directories=target/test-classes
```

Available endpoints:

- `POST /user/register`
- `POST /user/login`
- `POST /admin/add`
- `GET /admin/getAllUser`
- `GET /admin/getUserByName/{username}`
- `DELETE /admin/deleteUser/{userId}`
- `PUT /admin/account/deactivate`
- `PUT /admin/account/activate`
- `GET /admin/account/getActiveAccountsList`
- `GET /admin/account/getInActiveAccountsList`
- `GET /admin/accountList/ByAccountType/{accType}`
- `GET /admin/accountList/ByBranchType/{branchType}`
- `POST /account/create/{userId}`
- `GET /account/all/{userId}`
- `GET /account/balance`
- `GET /account/nominee`
- `PUT /account/updateNominee/{accountId}`
- `GET /account/getKycDetails`
- `PUT /account/updateKyc/{accountId}`
- `GET /account/getAccount/summary`
- `GET /card/block`
- `POST /card/apply/new`
- `PUT /card/setting`
- `POST /invest/now`

Access notes:

- `/user/register` and `/user/login` are public.
- `CUSTOMER` users can open accounts, manage cards, update KYC/nominees, and invest.
- `ADMIN` users can manage users and inspect account lists.
- JWT security is configured in `SecurityConfig`.

Example customer registration body:

```json
{
  "username": "john",
  "password": "john123",
  "name": "John Doe",
  "email": "john@example.com",
  "userType": "CUSTOMER"
}
```

Example account creation body:

```json
{
  "accountType": "SAVINGS",
  "balance": 50000,
  "proof": "PAN",
  "nominee": {
    "name": "Jane Doe",
    "accountNumber": 12345678,
    "relation": "SPOUSE",
    "age": 32,
    "gender": "FEMALE"
  }
}
```

## GitHub Metadata

- Suggested repository description: `Spring Boot banking API for customer accounts, cards, KYC, investments, and JWT-based security.`
- Suggested topics: `java`, `java-17`, `spring-boot`, `spring-security`, `spring-data-jpa`, `jwt`, `mysql`, `banking`, `rest-api`, `maven`, `lombok`, `investment`, `account-management`, `portfolio-project`
