# FlowMatic Auth Service

---

## Table of Contents

1. [Ye Project Karta Kya Hai?](#1-ye-project-karta-kya-hai)
2. [Design Patterns Jo Use Kiye (Factory + Strategy)](#2-design-patterns-jo-use-kiye)
3. [Request ka Poora Flow (Zoom Out View)](#3-request-ka-poora-flow)
4. [Folder Structure](#4-folder-structure)
5. [File by File Explanation](#5-file-by-file-explanation)
   - [pom.xml](#51-pomxml--dependencies-wali-file)
   - [application.properties](#52-applicationproperties--settings-wali-file)
   - [AuthApplication.java](#53-authapplicationjava--start-point)
   - Controllers
   - DTOs
   - Entity + Repository
   - Security (JWT ka dil)
   - Service + Strategy (Factory Pattern)
   - 📧 Email Verification (OTP) — Service Layer 🆕
   - Exceptions
6. [Do Aam Sawaal (FAQ)](#6-do-aam-sawaal-faq)

---

## 1. Ye Project Karta Kya Hai?

Ye ek **Authentication microservice** hai — matlab ye login/register ka kaam sambhalta hai. Iske kaam ye hain:

| Endpoint | Kaam | Public? |
|----------|------|---------|
| `POST /api/auth/register` | Naya user banao + email pe **OTP bhejo** (login abhi nahi karata) | Haan |
| `POST /api/auth/verify-email` | Email pe aaya **6-digit OTP** daal ke account verify karo | Haan |
| `POST /api/auth/resend-otp` | Naya OTP dobara bhejo (agar purana miss/expire ho gaya) | Haan |
| `POST /api/auth/login` | Email+password se login karo (**email verified hona zaroori**) | Haan |
| `POST /api/auth/refresh-token` | Purana token expire ho gaya toh naya lo | Haan |
| `GET /` | Service zinda hai ya nahi check karo | Haan |
| Baaki sab | Token chahiye hoga | Nahi |

**Zaroori badlaav (OTP verification):** ab register karne pe server **token nahi deta**. Pehle user ko email pe OTP aata hai, `verify-email` se account verify hota hai, **uske baad hi login** karke token milte hain. Jab tak email verify nahi, login **block** rahega (403).

Login successful hone par server tumhe **do token** deta hai:
- **Access Token** — chota (15 min), har request ke saath bhejte ho.
- **Refresh Token** — bada (7 din), sirf naya access token lene ke liye.

Ye tokens **JWT** (JSON Web Token) hote hain. Database mein session store nahi karte — isko **stateless** kehte hain. Server ko sirf token ka signature verify karna hota hai.

**Tech stack:** Java 17, Spring Boot 4.1, Spring Security, MySQL, JWT (jjwt library), **Spring Mail / JavaMailSender (SMTP OTP emails)**, Lombok.

---

## 2. Design Patterns Jo Use Kiye

### 🏭 Factory Pattern
`AuthStrategyFactory` ek "factory" hai. Tum use bolte ho *"mujhe LOCAL type ka authentication chahiye"* aur wo tumhe sahi object laa ke de deti hai. Tumhe khud `new` karke object banane ki zaroorat nahi.

### 🎯 Strategy Pattern
Har login ka tareeka (email/password, Google, Facebook...) ek alag **strategy** hai. Abhi sirf ek strategy hai — `EmailPasswordAuthStrategy`. Kal ko Google login add karna ho toh ek nayi class banao, factory apne aap use pakad legi. **Purana code chhedna nahi padega.** Yahi is pattern ki khoobsurti hai.

> Soch is tarah: Factory ek "dukaan" hai, Strategy "products" hain. Dukaan pe jaake bolo "LOCAL wala do", dukaan wo product de degi.

---

## 3. Request ka Poora Flow

### Signup + OTP verification ka safar (naya flow):

```
1) Client ──POST /register──► AuthController ──► AuthServiceImpl
                                                     │ user save (emailVerified=false)
                                                     ▼
                                                 OtpService ──► 6-digit OTP banao (hashed) ──► email_otp table
                                                     │
                                                     ▼
                                                 EmailService (@Async) ──► SMTP ──► User ke inbox mein OTP
                                                     │
                                                     ▼
                                              201 { "message": "OTP bhej diya, verify karo" }   (NO tokens)

2) Client ──POST /verify-email {email, otp}──► OtpService.verify() ──► sahi? ──► user.emailVerified = true
                                                                          │                    OTP delete
                                                                          ▼
                                                                   200 { "message": "Verified! Ab login karo" }

3) Client ──POST /login──► (ab email verified hai) ──► JWT tokens milte hain ✅
```

### Login ka safar (jab sab sahi ho):

```
Client  ──POST /api/auth/login──►  AuthController
                                        │
                                        ▼
                                   AuthService (interface)
                                        │
                                        ▼
                                   AuthServiceImpl
                                        │  "LOCAL strategy do"
                                        ▼
                                   AuthStrategyFactory  ──► EmailPasswordAuthStrategy
                                        │                        │ (DB se user, password match,
                                        │                        │  + emailVerified check → nahi toh 403)
                                        ▼                        ▼
                                   JwtUtil (2 token banao)   UserRepository ──► MySQL
                                        │
                                        ▼
                                   AuthResponse (JSON) ──► Client
```

### Protected request ka safar (token wali request):

```
Client ──(Header: Bearer <token>)──► JwtAuthFilter ──► token valid? ──► Yes ──► request aage
                                          │                                       jaati hai
                                          └── No/missing ──► AuthEntryPointJwt ──► 401 Unauthorized
```

---

## 4. Folder Structure

```
com.flowmatic.auth
├── AuthApplication.java          ← app yahaan se start hota hai (@EnableAsync bhi yahaan)
├── controller/                   ← HTTP request yahaan aati hai
│   ├── AuthController.java
│   └── WelcomeController.java
├── dto/                          ← request/response ke "dabbe" (data holders)
│   ├── AuthResponse.java
│   ├── ErrorResponse.java
│   ├── LoginRequest.java
│   ├── MessageResponse.java          🆕 (sirf ek message wapas bhejne ke liye)
│   ├── RefreshTokenRequest.java
│   ├── RegisterRequest.java
│   ├── ResendOtpRequest.java         🆕
│   └── VerifyEmailRequest.java       🆕
├── entity/                       ← database ki tables
│   ├── EmailOtp.java                 🆕 (email_otp table — OTP yahaan store hota hai)
│   ├── Role.java
│   └── User.java                     ✏️ (naya field: emailVerified)
├── repository/                   ← database se baat karne wali layer
│   ├── OtpRepository.java            🆕
│   └── UserRepository.java
├── security/                     ← JWT + Spring Security ka poora setup
│   ├── AuthEntryPointJwt.java
│   ├── CustomUserDetailsService.java
│   ├── JwtAuthFilter.java
│   ├── JwtUtil.java
│   └── SecurityConfig.java            ✏️ (2 naye public endpoints)
├── service/                      ← asli business logic
│   ├── AuthService.java              ✏️ (verifyEmail + resendOtp add)
│   ├── EmailService.java             🆕 (email bhejne ka contract)
│   ├── OtpService.java               🆕 (OTP banao/verify/resend ka contract)
│   ├── impl/
│   │   ├── AuthServiceImpl.java      ✏️ (register badla, verify/resend add)
│   │   ├── EmailServiceImpl.java     🆕 (JavaMailSender + @Async)
│   │   └── OtpServiceImpl.java       🆕 (OTP ka poora logic)
│   └── strategy/                 ← Factory + Strategy pattern yahaan hai
│       ├── AuthProviderType.java
│       ├── AuthStrategyFactory.java
│       ├── AuthenticatedUser.java
│       ├── AuthenticationStrategy.java
│       └── impl/EmailPasswordAuthStrategy.java   ✏️ (login pe emailVerified gate)
└── exception/                    ← errors handle karne wali layer
    ├── EmailNotVerifiedException.java    🆕 (403)
    ├── GlobalExceptionHandler.java       ✏️ (3 naye handlers)
    ├── InvalidCredentialsException.java
    ├── InvalidOtpException.java          🆕 (400)
    ├── InvalidTokenException.java
    ├── OtpResendCooldownException.java   🆕 (429)
    └── UserAlreadyExistsException.java
```

> 🆕 = nayi file · ✏️ = OTP feature ke liye badli gayi file

---

## 5. File by File Explanation

---

### 5.1 `pom.xml` — Dependencies wali file

Ye Maven ki file hai. Isme likha hota hai project ko kaun-kaun si libraries chahiye.

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
</parent>
```
- **Line 5-10:** Ye batata hai ki hum Spring Boot 4.1.0 ke bachche (child) hain. Isse saari default settings aur versions apne aap mil jaate hain — hume har cheez ka version likhne ki zaroorat nahi.

```xml
<properties>
    <java.version>17</java.version>
</properties>
```
- **Line 29-31:** Java 17 use karenge.

**Important dependencies (Line 32 se):**

| Dependency | Kaam |
|------------|------|
| `jjwt-api`, `jjwt-impl`, `jjwt-jackson` (Line 33-49) | JWT token banane/parse karne ke liye. `api` compile-time, baaki do `runtime` pe kaam karte hain. |
| `spring-boot-starter-data-jpa` (Line 50-53) | Database se baat karne ke liye (Hibernate/JPA). |
| `spring-boot-starter-security` (Line 54-57) | Login/authentication ka framework. |
| `spring-boot-starter-security-oauth2-client` (Line 58-61) | Google/OAuth login ke liye (abhi future ke liye rakha hai). |
| `spring-boot-starter-validation` (Line 62-65) | `@NotBlank`, `@Email` jaisi checks ke liye. |
| `spring-boot-starter-webmvc` (Line 66-69) | REST API banane ke liye (controllers, JSON, etc). |
| `spring-boot-starter-mail` 🆕 | OTP email bhejne ke liye — `JavaMailSender` (SMTP). Iske bina email verification kaam nahi karega. |
| `spring-boot-devtools` (Line 71-76) | Development mein auto-restart. |
| `mysql-connector-j` (Line 77-81) | MySQL database ka driver. |
| `lombok` (Line 82-86) | Boilerplate code (getters/setters/constructors) auto-generate karta hai. |

- **Line 87-111:** Ye saare `test` scope wale hain — sirf testing ke waqt use hote hain, production mein nahi jaate.

**Build section (Line 114 se):**
- **Line 116-127:** `spring-boot-maven-plugin` — final JAR banata hai. Lombok ko JAR se exclude kar diya hai (kyunki wo bas code generate karta hai, chalne ke liye zaroori nahi).
- **Line 128-163:** `maven-compiler-plugin` — Lombok ko "annotation processor" ke roop mein register karta hai taaki compile ke waqt getters/setters ban jaayein.

---

### 5.2 `application.properties` — Settings wali file

Yahaan project ki saari settings hoti hain. Code mein hardcode karne ke bajaye yahaan rakhte hain.

```properties
spring.application.name=auth
server.port=8080
```
- **Line 1-2:** App ka naam `auth`, aur ye port **8080** par chalega.

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/db_name?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC
spring.datasource.username=username_here
spring.datasource.password=${DB_PASSWORD}
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
```
- **Line 5-8:** MySQL database ka connection.
  - `flowmatic` naam ka database use karega. `createDatabaseIfNotExist=true` matlab agar database exist nahi karta toh khud bana lega.
  - Username `xxxxx`, password `xxxxx` production mein password rakhna zaroori hai.

```properties
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect
```
- **Line 11-14:** Hibernate (JPA) ki settings.
  - `ddl-auto=update` → tumhari `@Entity` classes dekh ke tables automatically bana/update kar deta hai. (Production mein iske jagah `validate` use karna behtar hota hai.)
  - `show-sql=false` → console mein SQL query print nahi karega.

```properties
app.jwt.secret=${JWT_SECRET}
app.jwt.access-token-expiry-ms=900000
app.jwt.refresh-token-expiry-ms=604800000
```
- **Line 17-19:** JWT ki settings. **Ye zaroori hain** kyunki `JwtUtil` inhe padhta hai.
  - `secret` → ye woh secret key hai jisse token sign hote hain. Isse kisi ko dena nahi! (Production mein isko environment variable mein rakhna chahiye, code/file mein nahi.)
  - `access-token-expiry-ms=900000` → 900000 ms = **15 minute**.
  - `refresh-token-expiry-ms=604800000` → 604800000 ms = **7 din**.

```properties
spring.mail.host=${MAIL_HOST:smtp.gmail.com}
spring.mail.port=${MAIL_PORT:587}
spring.mail.username=${MAIL_USERNAME:}
spring.mail.password=${MAIL_PASSWORD:}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
app.mail.from=${MAIL_FROM:no-reply@flowmatic.com}
```
- **Mail (SMTP) settings** 🆕 — OTP email yahaan se bhejte hain.
  - `${MAIL_HOST:smtp.gmail.com}` ka matlab: pehle **environment variable** `MAIL_HOST` dhoondho; nahi mila toh default `smtp.gmail.com`. Isi tarah baaki bhi. Isse Render/Railway pe secrets code mein daale bina set ho jaate hain.
  - **Gmail note:** normal password nahi chalega — Gmail ka **App Password** banana padta hai, aur port `587` + STARTTLS use hota hai (upar set hai).
  - `app.mail.from` — email kis address se "From" dikhega.

```properties
app.otp.length=6
app.otp.expiry-minutes=10
app.otp.max-attempts=5
app.otp.resend-cooldown-seconds=60
```
- **OTP policy settings** 🆕 — `OtpService` inhe `@Value` se padhta hai. Code chhede bina yahin tune kar sakte ho.
  - `length=6` → 6-digit code.
  - `expiry-minutes=10` → OTP 10 min mein expire.
  - `max-attempts=5` → 5 galat try ke baad OTP dead, naya maangna padega.
  - `resend-cooldown-seconds=60` → do resend ke beech kam se kam 60 sec ka gap.

```properties
# spring.security.oauth2.client.registration.google.client-id=...
```
- **Line 21-26:** Google OAuth login ke liye placeholder. Abhi comment (`#`) kiya hua hai — matlab abhi band hai, future mein enable karenge.

---

### 5.3 `AuthApplication.java` — Start Point

```java
@SpringBootApplication
@EnableAsync
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
```
- **`@SpringBootApplication`** — ye ek jaadui annotation hai. Ye teen cheezein ek saath karta hai: auto-configuration, component scanning (saari classes dhoondhna), aur configuration.
- **`@EnableAsync`** 🆕 — isse Spring `@Async` methods ko background thread mein chalata hai. Iski wajah se `EmailServiceImpl.sendOtpEmail(...)` request ko rokta nahi — email background mein jaata hai. (Iske bina `@Async` ka koi asar nahi hota.)
- **`main` method** — Java ka entry point. `SpringApplication.run(...)` poora Spring Boot app start kar deta hai. Bas isse hi sab kuch chalu hota hai.

> Ye file bahut chhoti hai par sabse important hai — yahi "ON button" hai.

---

### 5.4 `controller/WelcomeController.java` — Health Check

```java
@RestController
public class WelcomeController {

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> welcome() {
        return ResponseEntity.ok(Map.of(
                "message", "Welcome to the FlowMatic Auth service",
                "status", "UP",
                "timestamp", Instant.now()
        ));
    }
}
```
- **Line 10:** `@RestController` — batata hai ki ye class HTTP requests handle karegi aur seedha JSON return karegi.
- **Line 13:** `@GetMapping("/")` — jab koi `GET /` pe aayega, ye method chalega.
- **Line 14-20:** Ek simple JSON return karta hai jisme message, status "UP", aur current time hota hai. Isse pata chalta hai service **zinda** hai. Isko "health check" endpoint bolte hain.

---

### 5.5 `controller/AuthController.java` — Asli Dwaar (Main Gate)

Ye woh gate hai jahaan register/login/refresh **aur ab verify-email/resend-otp** ki requests aati hain.

```java
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
```
- **Line 14:** `@RestController` — REST API controller hai, JSON return karega.
- **Line 15:** `@RequestMapping("/api/auth")` — is class ke saare endpoints `/api/auth` se shuru honge.
- **Line 16:** `@RequiredArgsConstructor` (Lombok) — jitne bhi `final` fields hain, unke liye constructor apne aap bana deta hai. Isse Spring `authService` ko inject (dependency injection) kar deta hai.
- **Line 19:** `authService` — asli kaam ye karega. Controller ka kaam sirf request lena aur response dena hai; logic service mein hoti hai. (Isse "thin controller" bolte hain — accha practice.)

```java
    @PostMapping("/register")
    public ResponseEntity<MessageResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }
```
- `@PostMapping("/register")` → `POST /api/auth/register`.
  - `@RequestBody` → request ki JSON body ko `RegisterRequest` object mein badal do.
  - `@Valid` → us object ki validation checks chalao (jaise email sahi hai, password 8+ chars hai). Agar fail hui toh error aa jaayega.
- **✏️ Badlaav:** return type ab `AuthResponse` nahi, **`MessageResponse`** hai. Kyunki register ab tokens nahi deta — sirf "OTP bhej diya" wala message deta hai. Status phir bhi **201 CREATED** (kuch naya ban gaya).

```java
    @PostMapping("/verify-email")
    public ResponseEntity<MessageResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return ResponseEntity.ok(authService.verifyEmail(request));
    }
```
- 🆕 `POST /api/auth/verify-email`. Body mein `{ email, otp }`. OTP sahi hua toh account verified ho jaata hai aur **200 OK** + message aata hai.

```java
    @PostMapping("/resend-otp")
    public ResponseEntity<MessageResponse> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        return ResponseEntity.ok(authService.resendOtp(request));
    }
```
- 🆕 `POST /api/auth/resend-otp`. Body mein `{ email }`. Naya OTP bhejta hai (60 sec cooldown ke saath). **200 OK** + message.

```java
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
```
- **Line 26-29:** `POST /api/auth/login`. Login karta hai aur **200 OK** ke saath token wapas bhejta hai.

```java
    @PostMapping("/refresh-token")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request.getRefreshToken()));
    }
```
- **Line 31-34:** `POST /api/auth/refresh-token`. Refresh token le ke naya access token deta hai. `request.getRefreshToken()` se sirf token string nikaal ke service ko bhejta hai.

---

### 5.6 DTOs — Data ke "Dabbe"

**DTO** = Data Transfer Object. Ye bas data rakhne ke dabbe hain — request/response ke liye. Inme koi logic nahi hoti.

#### `dto/RegisterRequest.java`
```java
@Data
public class RegisterRequest {
    @NotBlank @Email
    private String email;

    @NotBlank @Size(min = 8, max = 72)
    private String password;

    @NotBlank
    private String fullName;
}
```
- **Line 8:** `@Data` (Lombok) — getters, setters, `toString`, `equals` sab auto bana deta hai.
- **Line 10-12:** `email` — khaali nahi ho sakta (`@NotBlank`) aur sahi email format hona chahiye (`@Email`).
- **Line 14-16:** `password` — khaali nahi, aur length **8 se 72** ke beech (72 kyunki BCrypt ki limit 72 bytes hai).
- **Line 18-19:** `fullName` — khaali nahi ho sakta.

> Ye `@Valid` annotation (controller mein) ke saath milke kaam karta hai. Agar rule toota toh request wahi ruk jaati hai.

#### `dto/LoginRequest.java`
```java
@Data
public class LoginRequest {
    @NotBlank @Email
    private String email;

    @NotBlank
    private String password;
}
```
- Login ke liye sirf email aur password chahiye. Email valid format ka hona chahiye, password bas khaali na ho.

#### `dto/RefreshTokenRequest.java`
```java
@Data
public class RefreshTokenRequest {
    @NotBlank
    private String refreshToken;
}
```
- Sirf ek field: `refreshToken`, jo khaali nahi ho sakta.

#### 🆕 `dto/VerifyEmailRequest.java`
```java
@Data
public class VerifyEmailRequest {
    @NotBlank @Email
    private String email;

    @NotBlank
    private String otp;
}
```
- `verify-email` endpoint ke liye. Email valid format ka ho, aur `otp` khaali na ho. (OTP ki length yahaan check nahi karte — asli match `OtpService` karta hai.)

#### 🆕 `dto/ResendOtpRequest.java`
```java
@Data
public class ResendOtpRequest {
    @NotBlank @Email
    private String email;
}
```
- `resend-otp` endpoint ke liye — sirf email chahiye.

#### 🆕 `dto/MessageResponse.java` — Sirf ek message
```java
@Data @Builder @AllArgsConstructor
public class MessageResponse {
    private String message;
}
```
- Jab response mein tokens nahi, sirf ek line ka message dena ho (register/verify/resend), tab ye use hota hai. Jaise `{ "message": "Email verified successfully. You can now log in." }`.

#### `dto/AuthResponse.java` — Server ka jawaab
```java
@Data @Builder @AllArgsConstructor
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private long expiresInSeconds;
    private String email;
    private String fullName;
}
```
- **Line 7-9:** 
  - `@Data` → getters/setters.
  - `@Builder` → object banane ka sundar tareeka (`.builder().accessToken(...).build()`).
  - `@AllArgsConstructor` → saare fields wala constructor.
- **Fields:** login/register ke baad client ko ye milta hai — dono token, token type (`Bearer`), access token kitni der mein expire hoga (seconds mein), aur user ka email + naam.

#### `dto/ErrorResponse.java` — Error ka standard format
```java
@Data @Builder @AllArgsConstructor
public class ErrorResponse {
    private Instant timestamp;   // kab error hua
    private int status;          // HTTP status code (jaise 401, 409)
    private String error;        // short naam (jaise "Unauthorized")
    private String message;      // detail message
    private String path;         // kaunse URL pe error aaya
}
```
- Jab bhi koi error hota hai, server isi format mein jawaab deta hai. Isse client ko har error ek jaisa dikhta hai — samajhna aasaan.

---

### 5.7 `entity/Role.java` — User ka Role

```java
public enum Role {
    USER,
    ADMIN
}
```
- Ek simple **enum** (fixed choices). User ya toh `USER` hoga ya `ADMIN`. Isse authorization mein kaam aata hai (kaun kya kar sakta hai).

---

### 5.8 `entity/User.java` — Database ki User Table

Ye class database mein `users` naam ki table ban jaati hai.

```java
@Entity
@Table(name="users", uniqueConstraints = @UniqueConstraint(columnNames ="email"))
@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class User {
```
- **Line 11:** `@Entity` — batata hai ye ek database table hai.
- **Line 12:** `@Table(...)` — table ka naam `users`, aur `email` unique hoga (do log same email se register nahi kar sakte).
- **Line 13-16:** Lombok annotations — getters/setters, builder, dono tarah ke constructors (`@NoArgsConstructor` JPA ke liye zaroori hai).

```java
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
```
- **Line 18-20:** `id` primary key hai. `IDENTITY` matlab database khud auto-increment karega (1, 2, 3...).

```java
    @Column(nullable = false, unique = true)
    private String email;
```
- **Line 22-23:** `email` — khaali nahi ho sakta, aur unique hai.

```java
    @Column(name="password_hash")
    private String passwordHash;
```
- **Line 25-26:** `passwordHash` — password ka **hashed** version. Dhyaan do: hum kabhi bhi plain password store nahi karte! Sirf BCrypt se hash karke rakhte hain. Column ka naam `password_hash` hoga.

```java
    @Column(nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;
```
- **Line 28-29:** `fullName` — zaroori.
- **Line 31-33:** `role` — `@Enumerated(EnumType.STRING)` matlab database mein `"USER"`/`"ADMIN"` text ke roop mein store hoga (number ke roop mein nahi — ye safe hai kyunki number order badalne se bug aa sakta hai).

```java
    @Column(nullable = false)
    private boolean enabled;

    // 🆕 True tabhi jab user email OTP se verify kar le. Tab tak login block.
    @Column(nullable = false)
    private boolean emailVerified;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
```
- `enabled` — account active hai ya band. **Admin** isse account disable kar sakta hai.
- **`emailVerified`** 🆕 — email verify hua ya nahi. Naye user ke liye `false` (primitive boolean default). `verify-email` success pe `true` ho jaata hai. **`enabled` se alag kyun?** — `enabled` ka matlab "admin ne band kiya" hai, aur `emailVerified` ka matlab "email confirm nahi hua". Do alag cheezein, isliye alag flags — warna dono ka matlab gadbada jaata.
- `createdAt` — account kab bana. `updatable = false` matlab ek baar set hone ke baad ye change nahi ho sakta.

```java
    @PrePersist
    void onCreated() {
        this.createdAt = Instant.now();
        if(this.role == null) {
            this.role = Role.USER;
        }
        this.enabled = true;
    }
```
- **Line 41-48:** `@PrePersist` — ye method **database mein save hone se theek pehle** apne aap chalta hai.
  - `createdAt` ko abhi ka time set karta hai.
  - Agar role set nahi kiya toh default `USER` bana deta hai.
  - `enabled` ko `true` kar deta hai (naya account by default active).

> Matlab jab bhi naya user save hoga, ye teen cheezein automatically ho jaayengi. Hume manually karne ki zaroorat nahi.

---

### 🆕 `entity/EmailOtp.java` — OTP ki Table

Ye class `email_otp` naam ki table ban jaati hai. Har email ka **ek** OTP yahaan store hota hai.

```java
@Entity
@Table(name = "email_otp", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class EmailOtp {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;          // email unique — ek email ka ek hi active OTP

    @Column(nullable = false)
    private String otpHash;        // OTP ka BCrypt hash — plaintext KABHI nahi

    @Column(nullable = false)
    private Instant expiresAt;     // kab expire hoga (10 min baad)

    @Column(nullable = false)
    private int attempts;          // kitni galat koshishein hui

    @Column(nullable = false)
    private Instant createdAt;     // aakhri baar kab issue hua (resend cooldown ke liye)
}
```
- `email` par **unique constraint** — ek email ka ek hi row rahega. Naya OTP aane par purana row **update** ho jaata hai (upsert), naya nahi banta.
- `otpHash` — bilkul password ki tarah, OTP ko bhi **hash** karke rakhte hain. DB leak ho bhi jaaye toh code kaam ka nahi.
- `attempts` — galat OTP daalne pe badhta hai. 5 ke baad OTP dead.
- `createdAt` — yahaan ise "aakhri baar kab bheja" samjho. Resend cooldown isi se naapte hain (`@PrePersist` nahi lagaya kyunki har resend pe ise refresh karna hota hai).

---

### 🆕 `repository/OtpRepository.java` — OTP ka DB access

```java
public interface OtpRepository extends JpaRepository<EmailOtp, Long> {
    Optional<EmailOtp> findByEmail(String email);
}
```
- `UserRepository` jaisa hi — `JpaRepository` extend karte hi `save`/`delete`/`findById` muft. `findByEmail` se email se OTP dhoondhte hain. `save`/`delete` `OtpService` ke andar use hote hain.

---

### 5.9 `repository/UserRepository.java` — Database se Baat

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```
- **Line 8:** `JpaRepository<User, Long>` — ye extend karte hi hume **muft mein** `save()`, `findById()`, `delete()`, `findAll()` jaise saare methods mil jaate hain. `User` = entity, `Long` = uski id ka type.
- **Line 9:** `findByEmail(...)` — Spring Data ka jaadu! Sirf method ka **naam** dekh ke Spring khud SQL query bana deta hai: *"email se user dhoondo"*. `Optional` isliye kyunki user mil bhi sakta hai, nahi bhi.
- **Line 10:** `existsByEmail(...)` — check karta hai ki is email se koi user pehle se hai ya nahi. `true`/`false` return karta hai.

> Kamaal ki baat: hume ek bhi line SQL nahi likhni padi. Bas method ka naam sahi rakho, Spring samajh jaata hai.

---

## 🔐 Security Layer (JWT ka Dil)

Ab aata hai sabse important part. Ye 5 files milke JWT authentication banati hain.

### 5.10 `security/JwtUtil.java` — Token Banane/Padhne wala Master

Ye class JWT token banati bhi hai aur unhe verify bhi karti hai.

```java
@Component
public class JwtUtil {

    private final SecretKey signingKey;
    private final long accessTokenExpiryMs;
    private final long refreshTokenExpiryMs;

    public JwtUtil(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiry-ms}") long accessTokenExpiryMs,
            @Value("${app.jwt.refresh-token-expiry-ms}") long refreshTokenExpiryMs) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenExpiryMs = accessTokenExpiryMs;
        this.refreshTokenExpiryMs = refreshTokenExpiryMs;
    }
```
- **Line 11:** `@Component` — Spring is class ka ek object bana ke rakhega (bean), taaki dusri jagah inject ho sake.
- **Line 14-16:** Teen fields — signing key (token sign karne wali key), aur dono expiry times.
- **Line 18-25:** Constructor. `@Value("${...}")` se `application.properties` wali values yahaan aa jaati hain.
  - **Line 22:** `Keys.hmacShaKeyFor(secret.getBytes())` — secret string se ek asli cryptographic key banata hai. Isi se token sign hote hain.

```java
    public String generateAccessToken(String email) {
        return buildToken(email, accessTokenExpiryMs, "access");
    }

    public String generateRefreshToken(String email) {
        return buildToken(email, refreshTokenExpiryMs, "refresh");
    }
```
- **Line 27-33:** Do methods — access token (15 min) aur refresh token (7 din) banate hain. Dono `buildToken` ko call karte hain, bas expiry aur type alag hai.

```java
    public long getAccessTokenExpirySeconds() {
        return accessTokenExpiryMs / 1000;
    }
```
- **Line 35-37:** Milliseconds ko seconds mein badal deta hai (client ko seconds mein bhejte hain).

```java
    private String buildToken(String subject, long expiryMs, String tokenType) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiryMs);

        return Jwts.builder()
                .subject(subject)
                .claim("type", tokenType)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }
```
- **Line 39-50:** Asli token yahaan banta hai.
  - **Line 40-41:** Abhi ka time (`now`) aur expiry time nikaalta hai.
  - **Line 43-49:** JWT banata hai:
    - `.subject(subject)` → token ke andar email daal do.
    - `.claim("type", tokenType)` → ek extra info "type" (access ya refresh). Isse hum baad mein farak kar sakte hain.
    - `.issuedAt(now)` → kab bana.
    - `.expiration(expiry)` → kab expire hoga.
    - `.signWith(signingKey)` → secret key se sign karo (taaki koi chhed na sake).
    - `.compact()` → sab kuch ek string mein badal do.

```java
    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractTokenType(String token) {
        return parseClaims(token).get("type", String.class);
    }
```
- **Line 52-58:** Token se email nikaalna (`getSubject`) aur type nikaalna ("access"/"refresh").

```java
    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }
```
- **Line 60-67:** Token valid hai ya nahi. Agar `parseClaims` bina error ke chal gaya toh valid (`true`). Agar token galat/expire/chheda hua hai toh exception aayega aur `false` return hoga.

```java
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
```
- **Line 69-75:** Token ko kholta (parse) hai.
  - `.verifyWith(signingKey)` → same key se signature check karta hai. Agar kisi ne token chheda hoga toh yahin fail ho jaayega.
  - `.getPayload()` → token ke andar ki saari info (email, type, expiry) nikaal deta hai.

> **Simple mein:** ye class ek "token factory + token checker" hai. Banao bhi, verify bhi karo.

---

### 5.11 `security/JwtAuthFilter.java` — Har Request ka Darban

Ye filter **har ek request** pe chalta hai aur check karta hai ki token laga hai ya nahi.

```java
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;
```
- **Line 21:** `OncePerRequestFilter` extend karta hai — matlab ek request pe **sirf ek baar** chalega.
- **Line 23-24:** Constants — hum `Authorization` header dhoondhenge, aur token `Bearer ` se shuru hoga (jaise `Bearer eyJhb...`).
- **Line 26-27:** `jwtUtil` (token verify karne) aur `userDetailsService` (user load karne) inject hote hain.

```java
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(HEADER_NAME);

        if(authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request,response);
            return;
        }
```
- **Line 29-36:**
  - Header padho.
  - **Line 33-36:** Agar header nahi hai YA `Bearer ` se shuru nahi hota → kuch mat karo, request ko aage bhej do. (Iska matlab public endpoints bina token ke chalte rahenge.)

```java
        String token = authHeader.substring(BEARER_PREFIX.length());

        if(jwtUtil.isTokenValid(token) && "access".equals(jwtUtil.extractTokenType(token))) {
            String email = jwtUtil.extractEmail(token);
```
- **Line 38:** `Bearer ` hata ke sirf token string nikaal lo.
- **Line 40:** Do checks — token valid ho **aur** type "access" ho. (Refresh token se login nahi kar sakte — ye important security check hai.)
- **Line 41:** Token se email nikaal lo.

```java
            if(SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request,response);
    }
```
- **Line 43:** Agar abhi tak koi authenticated nahi hai (dobara kaam na ho).
- **Line 44:** Email se poora user database se load karo.
- **Line 46:** Ek "authentication token" banao jisme user aur uske roles/authorities hote hain.
- **Line 47:** Request ki details (IP wagairah) attach karo.
- **Line 49:** `SecurityContextHolder` mein ye authentication set kar do — matlab Spring Security ko bata do ki **ye user logged in hai**. Ab is request ke baaki hisse mein user "known" hai.
- **Line 53:** Chahe kuch bhi ho, request ko aage bhej do (`filterChain.doFilter`).

> **Simple mein:** Ye darban har request pe token check karta hai. Sahi token → "andar aao, tum logged-in ho". Galat/nahi hai → chup-chaap aage bhej deta hai, aur agar wo page protected tha toh baad mein 401 mil jaata hai.

---

### 5.12 `security/CustomUserDetailsService.java` — User ko Spring ki Bhaasha mein Badalna

Spring Security ka apna user format hai (`UserDetails`). Ye class hamare `User` ko us format mein badalti hai.

```java
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException(("No user found with email: " + email)));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash())
                .disabled(!user.isEnabled())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                .build();
    }
}
```
- **Line 16:** `implements UserDetailsService` — Spring ka standard interface. Spring khud isko login ke waqt call karta hai.
- **Line 20:** `loadUserByUsername` — yahaan "username" matlab hamara email hai.
- **Line 21:** Database se email se user dhoondo. Nahi mila toh `UsernameNotFoundException` phenko.
- **Line 23-28:** Hamare `User` ko Spring wale `User` (UserDetails) mein badlo:
  - `.username(email)` → email.
  - `.password(passwordHash)` → hashed password (Spring khud match karega).
  - `.disabled(!user.isEnabled())` → agar user disabled hai toh login block.
  - `.authorities(...)` → role ko `ROLE_USER` ya `ROLE_ADMIN` format mein daalta hai (Spring ka convention `ROLE_` prefix maangta hai).

---

### 5.13 `security/AuthEntryPointJwt.java` — 401 Error dene wala

Jab koi bina valid token ke protected page pe jaata hai, toh ye class ek sundar JSON error bhejti hai.

```java
@Component
public class AuthEntryPointJwt implements AuthenticationEntryPoint {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message("A valid access token is required to access this resource")
                .path(request.getRequestURI())
                .build();

        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
```
- **Line 18:** `implements AuthenticationEntryPoint` — Spring is class ko tab call karta hai jab authentication fail ho.
- **Line 19:** `ObjectMapper` — Java object ko JSON string mein badalta hai. (Note: yahaan `tools.jackson.databind.ObjectMapper` import hua hai — ye Spring Boot 4 / Jackson 3 wala naya package hai.)
- **Line 22-24:** Response ko JSON banao aur status **401 Unauthorized** set karo.
- **Line 27-33:** Hamara standard `ErrorResponse` banao — time, status 401, error "Unauthorized", ek clear message, aur jis URL pe error aaya.
- **Line 35:** Us error ko JSON banake response mein likh do.

> **Simple mein:** Bina token protected page maanga? Ye class bolti hai "Bhai, pehle valid token laao" — aur wo bhi ek saaf-suthre JSON format mein.

---

### 5.14 `security/SecurityConfig.java` — Poori Security ka Blueprint

Ye sabse important config file hai. Yahaan decide hota hai kaun-si request public hai, kaun-si protected, aur JWT filter kahan lagega.

```java
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final CustomUserDetailsService userDetailsService;
    private final AuthEntryPointJwt authEntryPointJwt;
```
- **Line 17-18:** `@Configuration` + `@EnableWebSecurity` — batata hai ye Spring Security ki config file hai.
- **Line 22-24:** Teen cheezein inject karta hai — hamara JWT filter, user detail service, aur 401 handler.

```java
    private static final String[] PUBLIC_ENDPOINTS = {
            "/",
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh-token",
            "/api/auth/verify-email",   // 🆕
            "/api/auth/resend-otp"      // 🆕
    };
```
- Ye woh URLs hain jo **bina token** ke khulte hain (public). Baaki sab pe token chahiye. Logic simple hai — login/register/verify-email/resend-otp pe token toh ho hi nahi sakta (user ke paas abhi token hai hi nahi), isliye ye public hone chahiye.
- **✏️ Badlaav:** `verify-email` aur `resend-otp` add kiye. Agar ye add na karte toh Spring Security inhe block kar deta aur user kabhi verify hi nahi kar paata (chicken-and-egg).

```java
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authEntryPointJwt))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
```
- **Line 33-46:** Security ki saari rules yahaan set hoti hain:
  - **Line 36:** `.csrf(disable())` — CSRF protection band. (JWT + stateless API mein iski zaroorat nahi, ye browser cookies wali attack ke liye hota hai.)
  - **Line 37:** `SessionCreationPolicy.STATELESS` — **koi session nahi banao**. Har request khud-mukhtaar hai, token se hi pehchaan hoti hai. Yahi JWT ka asli faayda hai.
  - **Line 38:** Agar authentication fail ho toh `authEntryPointJwt` (hamara 401 handler) chalao.
  - **Line 39-41:** 
    - `PUBLIC_ENDPOINTS` sab ke liye khule (`permitAll`).
    - `anyRequest().authenticated()` → baaki har request pe login zaroori.
  - **Line 42:** Password check karne wala provider set karo.
  - **Line 43:** `addFilterBefore(jwtAuthFilter, ...)` — hamara JWT filter Spring ke normal login filter se **pehle** chalao. Isse token pehle check ho jaata hai.

```java
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }
```
- **Line 48-53:** Ye provider batata hai ki user kahan se laana hai (`userDetailsService`) aur password kaise match karna hai (`passwordEncoder`).

```java
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
```
- **Line 55-58:** `AuthenticationManager` — Spring ka standard authentication manager expose karta hai (zaroorat padne pe use hota hai).
- **Line 60-63:** `PasswordEncoder` → **BCrypt**. Ye passwords ko hash karne ka industry-standard tareeka hai. Ek hi password har baar alag hash deta hai (salt ki wajah se), isliye bahut safe.

> **Simple mein:** ye file security ka "rulebook" hai — kaun andar aa sakta hai bina puche, kaun nahi, aur password kaise handle honge.

---

## 🧠 Service Layer + Factory/Strategy Pattern

### 5.15 `service/AuthService.java` — Contract (Interface)

```java
public interface AuthService {
    MessageResponse register(RegisterRequest request);          // ✏️ ab MessageResponse
    AuthResponse login(LoginRequest request);
    AuthResponse refreshToken(String refreshToken);
    MessageResponse verifyEmail(VerifyEmailRequest request);    // 🆕
    MessageResponse resendOtp(ResendOtpRequest request);        // 🆕
}
```
- Ye ek **interface** hai — sirf batata hai ki "kya" hoga (register, login, refresh, **verify, resend**), "kaise" nahi.
- Faayda: controller sirf is interface pe depend karta hai. Kal ko implementation badal do, controller ko farak nahi padta. (Loose coupling — accha design.)
- **✏️ Badlaav:** `register` ab `MessageResponse` deta hai (tokens nahi), aur do naye methods `verifyEmail` + `resendOtp` add hue.

---

### 5.16 `service/impl/AuthServiceImpl.java` — Asli Business Logic

Yahaan register/login/refresh ka poora dimaag hai.

```java
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthStrategyFactory authStrategyFactory;
    private final OtpService otpService;                       // 🆕
```
- `@Service` — Spring ise service bean bana ke rakhega.
- Ab **paanch** cheezein chahiye — DB access, password encoder, JWT util, **strategy factory** (Factory pattern ka star), aur 🆕 **`otpService`** (OTP banane/verify/resend ke liye).

**Register method (✏️ badla — ab tokens nahi, OTP bhejta hai):**
```java
    @Override
    @Transactional
    public MessageResponse register(RegisterRequest request) {
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);

        // Email pehle se hai AUR verified — hard stop.
        if (user != null && user.isEmailVerified()) {
            throw new UserAlreadyExistsException("An account with this email already exists");
        }

        if (user == null) {
            user = User.builder()
                    .email(request.getEmail())
                    .passwordHash(passwordEncoder.encode(request.getPassword()))
                    .fullName(request.getFullName())
                    .role(Role.USER)
                    .build();
        } else {
            // Exist karta hai par verify nahi hua — dobara register karne do (lockout na ho).
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
            user.setFullName(request.getFullName());
        }

        userRepository.save(user);
        otpService.generateAndSend(user.getEmail());           // OTP banao + email bhejo

        return MessageResponse.builder()
                .message("Registration successful. An OTP has been sent to your email. "
                        + "Please verify to activate your account.")
                .build();
    }
```
- **Sabse bada change yahi hai.** Pehle register turant token de deta tha (auto-login). Ab:
  - Agar email **pehle se verified** hai → `UserAlreadyExistsException` (409). Ye asli duplicate hai.
  - Agar email exist karta hai par **verify nahi hua** (user ne tab band kar diya tha, OTP expire ho gaya) → use **dobara register** karne dete hain, fresh details + naya OTP. Warna wo email hamesha ke liye phas jaati.
  - Naya user `emailVerified = false` ke saath banta hai (builder mein set nahi kiya toh default `false`).
  - `otpService.generateAndSend(...)` OTP banata hai aur email bhejta hai.
  - Return sirf ek **message** — **koi token nahi**.
- `@Transactional` isliye kyunki user save + OTP ka DB kaam ek saath hona chahiye (ya dono, ya koi nahi).

**Login method (yahaan Factory use hota hai):**
```java
    @Override
    public AuthResponse login(LoginRequest request) {
        AuthenticatedUser authenticatedUser = authStrategyFactory
                .getStrategy(AuthProviderType.LOCAL)
                .authenticate(request);

        return buildAuthResponse(authenticatedUser.getEmail(), authenticatedUser.getFullName());
    }
```
- **Line 49-51:** **Yahi hai Factory pattern ka jaadu!**
  - `authStrategyFactory.getStrategy(AuthProviderType.LOCAL)` → factory se "LOCAL" wali strategy maango.
  - `.authenticate(request)` → us strategy se authenticate karwao (email/password check).
- **Line 53:** Sahi hone par token banake response do.

> Dhyaan do: ye method **nahi jaanta** ki authentication kaise ho raha hai. Wo detail strategy ke paas hai. Kal ko Google login add karo, bas yahaan `LOCAL` ki jagah `GOOGLE` likho — baaki sab kaam karta rahega.

**Refresh token method:**
```java
    @Override
    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtUtil.isTokenValid(refreshToken) || !"refresh".equals(jwtUtil.extractTokenType(refreshToken))) {
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }

        String email = jwtUtil.extractEmail(refreshToken);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new InvalidTokenException("Refresh token is invalid or expired"));

        return buildAuthResponse(user.getEmail(), user.getFullName());
    }
```
- **Line 58-60:** Do check — token valid ho **aur** type "refresh" ho. (Access token se refresh nahi kar sakte.) Fail hua toh error.
- **Line 62:** Token se email nikaalo.
- **Line 63-64:** User database mein hai ya nahi confirm karo (ho sakta hai user delete ho gaya ho).
- **Line 66:** Naye fresh token banake do.

**🆕 Verify email method:**
```java
    @Override
    @Transactional
    public MessageResponse verifyEmail(VerifyEmailRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidOtpException("No account found for this email"));

        if (user.isEmailVerified()) {
            return MessageResponse.builder()
                    .message("Email is already verified. You can log in.")
                    .build();
        }

        otpService.verify(request.getEmail(), request.getOtp());   // galat hua toh yahin exception

        user.setEmailVerified(true);
        userRepository.save(user);

        return MessageResponse.builder()
                .message("Email verified successfully. You can now log in.")
                .build();
    }
```
- User dhoondo. Nahi mila → `InvalidOtpException`.
- Pehle se verified hai → seedha message, dobara verify karne ki zaroorat nahi.
- `otpService.verify(...)` OTP check karta hai (expire/attempts/match). **Kuch bhi galat** hua toh wahi exception phenk deta hai — yahaan `if` likhne ki zaroorat nahi.
- Sab sahi → `emailVerified = true` set karke save. Ab user login kar sakta hai.

**🆕 Resend OTP method:**
```java
    @Override
    @Transactional
    public MessageResponse resendOtp(ResendOtpRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidOtpException("No account found for this email"));

        if (user.isEmailVerified()) {
            return MessageResponse.builder()
                    .message("Email is already verified. You can log in.")
                    .build();
        }

        otpService.resend(request.getEmail());     // cooldown check + naya OTP

        return MessageResponse.builder()
                .message("A new OTP has been sent to your email.")
                .build();
    }
```
- Same shuruaat (user dhoondo, already-verified check). Fir `otpService.resend(...)` — ye 60 sec cooldown check karke naya OTP bhejta hai.

**Common helper:**
```java
    private AuthResponse buildAuthResponse(String email, String fullName) {
        String accessToken = jwtUtil.generateAccessToken(email);
        String refreshToken = jwtUtil.generateRefreshToken(email);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresInSeconds(jwtUtil.getAccessTokenExpirySeconds())
                .email(email)
                .fullName(fullName)
                .build();
    }
```
- **Line 69-81:** Teeno methods (register/login/refresh) yahi call karte hain. Do token banata hai aur ek poora `AuthResponse` return karta hai. Ye **DRY principle** hai (Don't Repeat Yourself) — code repeat nahi kiya.

---

### 5.17 Strategy Pattern ki Files

#### `service/strategy/AuthProviderType.java`
```java
public enum AuthProviderType {
    LOCAL
}
```
- Login ke tareeke. Abhi sirf `LOCAL` (email/password). Future mein `GOOGLE`, `FACEBOOK`, `GITHUB` add ho sakte hain.

#### `service/strategy/AuthenticationStrategy.java` — Strategy ka Contract
```java
public interface AuthenticationStrategy {
    AuthProviderType getProviderType();
    AuthenticatedUser authenticate(Object credentials);
}
```
- **Line 5:** `getProviderType()` — ye strategy kis type ki hai (LOCAL/GOOGLE...).
- **Line 7:** `authenticate(...)` — asli authentication. `Object credentials` isliye general rakha hai kyunki har provider ka input alag ho sakta hai (email/password vs Google token).
- **Har naya login tareeka is interface ko implement karega.** Yahi strategy pattern ki jaan hai.

#### `service/strategy/AuthenticatedUser.java` — Result ka Dabba
```java
@Data @Builder @AllArgsConstructor
public class AuthenticatedUser {
    private String email;
    private String fullName;
    private boolean newUser;
}
```
- Authentication successful hone par strategy ye return karti hai — email, naam, aur `newUser` (Google login mein kaam aayega jab pehli baar login karne wala user apne aap register ho jaata hai).

#### `service/strategy/AuthStrategyFactory.java` — 🏭 THE FACTORY

Ye poore project ki sabse clever file hai. Dhyaan se samjho:

```java
@Component
public class AuthStrategyFactory {

    private final Map<AuthProviderType, AuthenticationStrategy> strategies;

    public AuthStrategyFactory(List<AuthenticationStrategy> strategyBeans) {
        this.strategies = strategyBeans.stream()
                .collect(Collectors.toMap(AuthenticationStrategy::getProviderType, Function.identity()));
    }
```
- **Line 13:** Ek `Map` — key = provider type (LOCAL), value = us type ki strategy.
- **Line 15:** Constructor mein Spring **saari** `AuthenticationStrategy` classes ki `List` khud daal deta hai! (Kyunki har strategy `@Component` hai.) Ye Spring ka jaadu hai — jitni bhi strategies hongi, sab yahaan aa jaayengi.
- **Line 16-17:** Us list ko Map mein badal deta hai — har strategy ka type key ban jaata hai. Matlab:
  - `EmailPasswordAuthStrategy` → key `LOCAL`.
  - (Future) `GoogleAuthStrategy` → key `GOOGLE`.

```java
    public AuthenticationStrategy getStrategy(AuthProviderType type) {
        AuthenticationStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("No authentication strategy registered for provider: " + type);
        }
        return strategy;
    }
}
```
- **Line 20-26:** Tum type do (jaise `LOCAL`), ye us type ki strategy laa deta hai. Agar us type ki koi strategy nahi hai toh error.

> **Factory + Strategy ka combo — asli faayda:**
> Kal ko Google login add karna ho toh:
> 1. `AuthProviderType` mein `GOOGLE` add karo.
> 2. Ek nayi class `GoogleAuthStrategy` banao jo `AuthenticationStrategy` implement kare, `getProviderType()` mein `GOOGLE` return kare, aur uspe `@Component` lagao.
> 
> **Bas! Factory apne aap use pakad legi.** `AuthStrategyFactory` ya `AuthServiceImpl` mein ek line bhi change nahi karni padegi. Isko **Open/Closed Principle** kehte hain — extension ke liye khula, modification ke liye band.

#### `service/strategy/impl/EmailPasswordAuthStrategy.java` — Asli LOCAL Strategy

```java
@Component
@RequiredArgsConstructor
public class EmailPasswordAuthStrategy implements AuthenticationStrategy {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public AuthProviderType getProviderType() {
        return AuthProviderType.LOCAL;
    }
```
- **Line 14:** `@Component` — isi wajah se factory ise apne aap pakad leti hai.
- **Line 22-24:** Ye strategy `LOCAL` type ki hai — factory isi key pe register karegi.

```java
    @Override
    public AuthenticatedUser authenticate(Object credentials) {
        LoginRequest request = (LoginRequest) credentials;

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        // 🆕 Gate: sahi password kaafi nahi — email verified hona bhi zaroori.
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException("Email not verified. Please verify your email before logging in.");
        }

        return AuthenticatedUser.builder()
                .email(user.getEmail())
                .fullName(user.getFullName())
                .newUser(false)
                .build();
    }
}
```
- `credentials` ko `LoginRequest` mein cast karo (kyunki LOCAL login mein email/password aata hai).
- Email se user dhoondo. Nahi mila toh `InvalidCredentialsException`.
- `passwordEncoder.matches(...)` — user ka diya password aur DB ka hash match karta hai. Nahi mila toh error.
  - **Security note:** dono cases (user nahi mila / password galat) mein **same message** "Invalid email or password" dete hain. Isse hacker ko pata nahi chalta ki email exist karta hai ya nahi. Ye jaanbujh ke kiya gaya hai.
- **🆕 emailVerified gate:** password sahi hone ke baad check karte hain ki email verified hai ya nahi. Nahi hai toh `EmailNotVerifiedException` (→ **403**). Ye gate **yahin** kyun? Kyunki user object yahaan already load hai (extra DB query nahi), aur alag exception hone se client "galat password" (401) aur "email verify karo" (403) mein farak kar sakta hai.
- Sab sahi? Toh `AuthenticatedUser` return karo. `newUser(false)` kyunki ye pehle se registered user hai.

---

## 📧 Email Verification (OTP) — Service Layer 🆕

Ye do services milke OTP ka poora kaam sambhalte hain. `OtpService` "dimaag" hai (OTP banao/verify/resend), `EmailService` "haath" hai (email bhejo).

### 🆕 `service/OtpService.java` + `service/EmailService.java` — Contracts

```java
public interface OtpService {
    void generateAndSend(String email);   // register ke waqt (no cooldown)
    void resend(String email);            // dobara bhejo (cooldown ke saath)
    void verify(String email, String otp);// code check karo (galat → exception)
}

public interface EmailService {
    void sendOtpEmail(String to, String code);
}
```
- Interfaces isliye taaki baaki code implementation pe nahi, contract pe depend kare (jaise `AuthService`). Kal ko email SendGrid API se bhejni ho toh sirf `EmailServiceImpl` badlo.

### 🆕 `service/impl/EmailServiceImpl.java` — Email bhejne wala (async)

```java
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;

    @Value("${app.otp.expiry-minutes}")
    private long expiryMinutes;

    @Async
    @Override
    public void sendOtpEmail(String to, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject("Your FlowMatic verification code");
        message.setText(
                "Your verification code is: " + code + "\n\n"
                        + "This code will expire in " + expiryMinutes + " minutes.\n"
                        + "If you did not request this, please ignore this email.");
        mailSender.send(message);
    }
}
```
- `JavaMailSender` — Spring ka email bhejne wala tool. `spring-boot-starter-mail` + `application.properties` ki `spring.mail.*` settings se ye bean apne aap ban jaata hai.
- **`@Async`** — sabse important. SMTP thodi der leta hai (1-2 sec). `@Async` isse **background thread** mein bhej deta hai, taaki `/register` ka response turant chala jaaye, email wait na karwaye. (Isi ke liye `AuthApplication` pe `@EnableAsync` lagaya tha.)
- Plaintext code **parameter** mein aata hai (DB se lazy-load nahi), isliye async thread mein koi transaction problem nahi.

### 🆕 `service/impl/OtpServiceImpl.java` — OTP ka poora dimaag

```java
@Service
@RequiredArgsConstructor
public class OtpServiceImpl implements OtpService {

    private final OtpRepository otpRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.otp.length}")            private int otpLength;
    @Value("${app.otp.expiry-minutes}")    private long expiryMinutes;
    @Value("${app.otp.max-attempts}")      private int maxAttempts;
    @Value("${app.otp.resend-cooldown-seconds}") private long resendCooldownSeconds;
```
- Config values `application.properties` se `@Value` se aati hain — code chhede bina policy badal sakte ho.
- `SecureRandom` — normal `Random` nahi, **cryptographically secure** random, taaki OTP guess na ho sake.

**generateAndSend + resend:**
```java
    @Override @Transactional
    public void generateAndSend(String email) {
        issueOtp(email);                    // register pe — cooldown nahi
    }

    @Override @Transactional
    public void resend(String email) {
        otpRepository.findByEmail(email).ifPresent(existing -> {
            long secondsSince = Duration.between(existing.getCreatedAt(), Instant.now()).getSeconds();
            if (secondsSince < resendCooldownSeconds) {
                throw new OtpResendCooldownException(
                        "Please wait " + (resendCooldownSeconds - secondsSince)
                                + " seconds before requesting another code.");
            }
        });
        issueOtp(email);
    }
```
- `resend` pehle **cooldown** check karta hai — agar aakhri OTP 60 sec ke andar bheja tha toh `OtpResendCooldownException` (→ **429**). Isse koi spam nahi kar sakta.

**verify (order important hai):**
```java
    @Override @Transactional
    public void verify(String email, String otp) {
        EmailOtp record = otpRepository.findByEmail(email)
                .orElseThrow(() -> new InvalidOtpException("No OTP found. Please request a new code."));

        if (Instant.now().isAfter(record.getExpiresAt())) {
            otpRepository.delete(record);
            throw new InvalidOtpException("OTP has expired. Please request a new code.");
        }
        if (record.getAttempts() >= maxAttempts) {
            otpRepository.delete(record);
            throw new InvalidOtpException("Too many incorrect attempts. Please request a new code.");
        }
        if (!passwordEncoder.matches(otp, record.getOtpHash())) {
            record.setAttempts(record.getAttempts() + 1);
            otpRepository.save(record);
            throw new InvalidOtpException("Invalid OTP.");
        }
        otpRepository.delete(record);       // success — OTP consume (reuse na ho)
    }
```
- Checks ka **order** soch samajh ke rakha hai: pehle exist karta hai? → expire toh nahi? → 5 attempts se zyada toh nahi? → **fir** code match. Galat code pe `attempts++` aur save. Sahi code pe row **delete** — taaki wahi OTP dobara use na ho (replay attack se bachao).

**issueOtp (upsert — ek subtle bug se bachne ke liye):**
```java
    private void issueOtp(String email) {
        String code = generateCode();
        Instant now = Instant.now();

        EmailOtp otp = otpRepository.findByEmail(email).orElseGet(EmailOtp::new);
        otp.setEmail(email);
        otp.setOtpHash(passwordEncoder.encode(code));
        otp.setExpiresAt(now.plus(expiryMinutes, ChronoUnit.MINUTES));
        otp.setAttempts(0);
        otp.setCreatedAt(now);

        otpRepository.save(otp);
        emailService.sendOtpEmail(email, code);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(otpLength);
        for (int i = 0; i < otpLength; i++) {
            sb.append(secureRandom.nextInt(10));
        }
        return sb.toString();
    }
```
- **`issueOtp` upsert karta hai** — email ka purana row mila toh usko **update** karo, nahi mila toh naya banao.
- **Yahaan ek clever decision hai:** pehle mann kiya "purana delete karo, naya insert karo". Par Hibernate ek flush mein **pehle INSERT, baad mein DELETE** karta hai — toh same unique `email` pe delete-then-insert **unique constraint tod** deta. Isliye delete+insert ke bajaye **update-in-place** (upsert) kiya. Code mein comment bhi daala hai taaki koi galti se "simplify" karke bug wapas na le aaye.
- `generateCode()` — `otpLength` (6) baar 0-9 ka random digit jod ke code banata hai. Leading zero bhi valid (string ki tarah compare hota hai).

---

## ⚠️ Exception Layer (Errors ko Sambhalna)

### 5.18 Custom Exceptions (chhoti files)

Ye sab bilkul simple hain — bas custom error types hain (har ek `RuntimeException` extend karta hai).

#### `exception/UserAlreadyExistsException.java`
```java
public class UserAlreadyExistsException extends RuntimeException {
    public UserAlreadyExistsException(String message) {
        super(message);
    }
}
```
- Jab email pehle se registered ho tab ye phenka jaata hai. `RuntimeException` extend karta hai (unchecked exception).

#### `exception/InvalidCredentialsException.java`
- Same structure. Galat email/password pe phenka jaata hai.

#### `exception/InvalidTokenException.java`
- Same structure. Galat/expired refresh token pe phenka jaata hai.

#### 🆕 `exception/EmailNotVerifiedException.java`
- Password sahi hai par email verify nahi hua — login block. → **403 FORBIDDEN**.

#### 🆕 `exception/InvalidOtpException.java`
- OTP galat / expire / attempts khatam / account nahi mila. → **400 BAD REQUEST**.

#### 🆕 `exception/OtpResendCooldownException.java`
- 60 sec ke andar dobara OTP maanga. → **429 TOO MANY REQUESTS**.

> Ye alag-alag exceptions isliye banaye taaki `GlobalExceptionHandler` har ek ke liye alag HTTP status de sake.

### 5.19 `exception/GlobalExceptionHandler.java` — Ek Jagah Saare Errors Handle

Ye class poore app ke errors ek jagah pakad ke sundar JSON banati hai. Ise ek "central error control room" samjho.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
```
- **Line 16:** `@RestControllerAdvice` — ye poore application ke controllers ke liaye errors sunta hai. Ek jagah likho, sab jagah kaam kare.

```java
    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUserExists(UserAlreadyExistsException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }
```
- **Line 19-22:** `UserAlreadyExistsException` aaye toh **409 CONFLICT** do (409 ka matlab "ye cheez pehle se hai").

```java
    @ExceptionHandler(InvalidCredentialsException.class)
    ... return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req);

    @ExceptionHandler(InvalidTokenException.class)
    ... return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req);
```
- **Line 24-32:** Galat password ya galat token → **401 UNAUTHORIZED**.

```java
    @ExceptionHandler(EmailNotVerifiedException.class)
    ... return build(HttpStatus.FORBIDDEN, ex.getMessage(), req);            // 403

    @ExceptionHandler(InvalidOtpException.class)
    ... return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);          // 400

    @ExceptionHandler(OtpResendCooldownException.class)
    ... return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), req);    // 429
```
- 🆕 Teen naye OTP-related handlers: email verify nahi → **403**, OTP galat → **400**, resend cooldown → **429**. Har ek same `build(...)` helper use karta hai, bas status alag.

```java
    @ExceptionHandler(AccessDeniedException.class)
    ... return build(HttpStatus.FORBIDDEN, "You do not have permission...", req);
```
- **Line 34-37:** User logged-in hai par usko permission nahi (jaise USER ne ADMIN ka page khola) → **403 FORBIDDEN**.

```java
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, message, req);
    }
```
- **Line 39-45:** Validation fail hui (jaise email galat format, password chhota) → **400 BAD REQUEST**. Saare field errors ko comma se jod ke ek message banata hai.

```java
    @ExceptionHandler(Exception.class)
    ... return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req);
```
- **Line 47-50:** Koi bhi anjaan error → **500 INTERNAL SERVER ERROR**. Yahaan generic message dete hain taaki internal details (stack trace) hacker ko na dikhein.

```java
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(req.getRequestURI())
                .build();
        return ResponseEntity.status(status).body(body);
    }
```
- **Line 52-61:** Common helper. Har error ka standard `ErrorResponse` (time, status, error naam, message, path) banata hai. Isse har error ek jaisa dikhta hai. Fir se **DRY principle**.

---

### 5.20 `test/AuthApplicationTests.java` — Basic Test

```java
@SpringBootTest
class AuthApplicationTests {
    @Test
    void contextLoads() {
    }
}
```
- **Line 6:** `@SpringBootTest` — poora Spring context load karke test karta hai.
- **Line 9-11:** `contextLoads()` — ek khaali test. Iska matlab: agar app bina error ke start ho gaya toh test pass. Ye sabse basic "smoke test" hai — batata hai ki configuration mein koi badi galti nahi hai.

---

## 6. Do Aam Sawaal (FAQ)

**Q: JWT stateless kyun accha hai?**  
Server ko session yaad rakhne ki zaroorat nahi. Token khud mein saari info rakhta hai. Isse app ko scale karna aasaan hai — 10 servers ho toh bhi kaam karega, kyunki koi shared session store nahi chahiye.

**Q: Access token chhota (15 min) aur refresh token bada (7 din) kyun?**  
Agar access token chori ho jaaye toh sirf 15 min ke liye use ho sakta hai. Refresh token safe jagah rakhte hain aur usse baar-baar naya access token le lete hain. Balance — security bhi, convenience bhi.

**Q: Naya login provider (jaise Google) kaise add karun?**  
Teen step:
1. `AuthProviderType` enum mein `GOOGLE` add karo.
2. `GoogleAuthStrategy` class banao — `AuthenticationStrategy` implement karo, `@Component` lagao, `getProviderType()` mein `GOOGLE` return karo.
3. `AuthServiceImpl` mein sahi jagah `getStrategy(AuthProviderType.GOOGLE)` use karo.
Factory baaki sab khud sambhal legi. **Purani koi file chhedni nahi padegi.**

**Q: Password kahan store hota hai?**  
Kabhi bhi plain text mein nahi! Sirf **BCrypt hash** store hota hai (`password_hash` column). Login pe `passwordEncoder.matches()` compare karta hai.

**Q: OTP kahan aur kaise store hota hai?**  
`email_otp` table mein, aur wo bhi **hashed** (BCrypt) — password ki tarah. Har email ka ek hi row rahta hai (upsert). 10 min baad expire, 5 galat try ke baad dead.

**Q: Register ke baad login kyun nahi hota?**  
Kyunki ab email verify karna zaroori hai. Register OTP bhejta hai (token nahi). `verify-email` se account verify karo, **fir** login karke token lo. Tab tak login 403 dega.

**Q: Email background mein kaise jaati hai?**  
`EmailServiceImpl.sendOtpEmail` pe `@Async` laga hai (aur `AuthApplication` pe `@EnableAsync`). Isse SMTP ka wait request ko block nahi karta.

**Q: Deploy karte waqt kya set karna hai?**  
SMTP env vars: `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`. Gmail use kar rahe ho toh **App Password** banao (normal password nahi chalega).

**Q: Request ka poora flow kya hai?**  
- **Register:** `Controller → AuthServiceImpl → OtpService → EmailService (@Async SMTP)` + user save (unverified), wapas sirf message.
- **Login:** `Controller → Service → Factory → Strategy (password + emailVerified check) → JwtUtil → AuthResponse (tokens)`.

---

## 🎯 Ek Line mein Summary

> Ye ek **stateless JWT auth service** hai jo **Factory + Strategy pattern** use karti hai taaki naye login methods (Google, Facebook, etc.) bina purana code chhede aasaani se add ho sakein. Ab isme **OTP email verification** bhi hai — register OTP bhejta hai (token nahi), `verify-email` account verify karta hai, aur jab tak verify na ho login **block** rehta hai. OTP hashed store hota hai, email `@Async` SMTP se jaati hai. Controller patla hai, logic service mein hai, security JWT filter se hoti hai, aur saare errors ek central handler se sundar JSON banke aate hain.

Bhai, ab har developer ye padh ke poora project samajh sakta hai. Koi doubt ho toh is doc ka relevant section dobara padho — sab line-by-line yahaan hai! 🚀
