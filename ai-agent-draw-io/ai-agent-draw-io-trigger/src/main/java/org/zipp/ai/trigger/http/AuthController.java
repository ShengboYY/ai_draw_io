package org.zipp.ai.trigger.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.dto.LoginRequestDTO;
import org.zipp.ai.api.dto.LoginResponseDTO;
import org.zipp.ai.api.dto.PasswordResetConfirmRequestDTO;
import org.zipp.ai.api.dto.PasswordResetConfirmResponseDTO;
import org.zipp.ai.api.dto.PasswordResetRequestDTO;
import org.zipp.ai.api.dto.RegisterAccountRequestDTO;
import org.zipp.ai.api.dto.RegisterAccountResponseDTO;
import org.zipp.ai.api.dto.ResendVerificationRequestDTO;
import org.zipp.ai.api.dto.VerifyEmailResponseDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.EmailVerificationResult;
import org.zipp.ai.domain.account.model.valobj.LoginAccountCommand;
import org.zipp.ai.domain.account.model.valobj.LoginResult;
import org.zipp.ai.domain.account.model.valobj.PasswordResetResult;
import org.zipp.ai.domain.account.model.valobj.RegisterAccountCommand;
import org.zipp.ai.domain.account.service.IAccountService;
import org.zipp.ai.domain.account.service.RateLimitExceededException;
import org.zipp.ai.domain.account.service.UsageCounterRateLimiter;
import org.zipp.ai.domain.account.service.UsageLimitRule;
import org.zipp.ai.trigger.http.service.AuthenticatedSessionUser;
import org.zipp.ai.trigger.http.service.AuthenticatedUserPrincipal;
import org.zipp.ai.types.enums.ResponseCode;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Registration, email verification, and password login/logout endpoints. Login writes a real Spring
 * Security context to the HTTP session; logout invalidates that session so the cookie is unusable
 * even if it lingers on the client.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String SUCCESS = "0000";
    private static final String FAILURE = "0001";
    private static final SimpleGrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");
    private static final String REGISTER_VERIFY_IP_BUCKET = "auth.register-verify-ip";
    private static final String PASSWORD_RESET_IP_BUCKET = "auth.password-reset-ip";
    private static final String LOGIN_IP_BUCKET = "auth.login-ip";
    private static final UsageLimitRule REGISTER_VERIFY_IP_HOURLY = UsageLimitRule.of(20, Duration.ofHours(1));
    private static final UsageLimitRule REGISTER_VERIFY_IP_DAILY = UsageLimitRule.of(100, Duration.ofDays(1));
    private static final UsageLimitRule PASSWORD_RESET_IP_HOURLY = UsageLimitRule.of(20, Duration.ofHours(1));
    private static final UsageLimitRule LOGIN_IP_HOURLY = UsageLimitRule.of(30, Duration.ofHours(1));

    @Resource
    private IAccountService accountService;

    @Resource
    private SecurityContextRepository securityContextRepository;

    @Resource
    private UsageCounterRateLimiter usageCounterRateLimiter = new UsageCounterRateLimiter();

    @PostMapping("/register")
    public Response<RegisterAccountResponseDTO> register(@RequestBody RegisterAccountRequestDTO request,
                                                         HttpServletRequest servletRequest) {
        try {
            enforceRegisterVerifyIpLimit(servletRequest);
            accountService.register(RegisterAccountCommand.builder()
                    .email(request == null ? null : request.getEmail())
                    .rawPassword(request == null ? null : request.getPassword())
                    .build());
        } catch (RateLimitExceededException e) {
            return rateLimited(e);
        } catch (IllegalArgumentException e) {
            // Input-shape errors (missing email / short password) are the only case we surface.
            return Response.<RegisterAccountResponseDTO>builder()
                    .code(FAILURE).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("register failed", e);
            return Response.<RegisterAccountResponseDTO>builder()
                    .code(FAILURE).info("register failed").build();
        }
        // Same body for CREATED / RESENT_PENDING / ALREADY_REGISTERED — no enumeration oracle.
        return Response.<RegisterAccountResponseDTO>builder()
                .code(SUCCESS).info("成功")
                .data(RegisterAccountResponseDTO.builder().submitted(true).build())
                .build();
    }

    public Response<RegisterAccountResponseDTO> register(RegisterAccountRequestDTO request) {
        return register(request, null);
    }

    @GetMapping("/verify-email")
    public Response<VerifyEmailResponseDTO> verifyEmail(@RequestParam("token") String token) {
        try {
            EmailVerificationResult result = accountService.verifyEmail(token);
            return Response.<VerifyEmailResponseDTO>builder()
                    .code(SUCCESS).info("成功")
                    .data(VerifyEmailResponseDTO.builder().status(result.name()).build())
                    .build();
        } catch (Exception e) {
            log.error("verify-email failed", e);
            return Response.<VerifyEmailResponseDTO>builder()
                    .code(FAILURE).info("verify email failed").build();
        }
    }

    @PostMapping("/resend-verification")
    public Response<Void> resendVerification(@RequestBody ResendVerificationRequestDTO request,
                                             HttpServletRequest servletRequest) {
        try {
            enforceRegisterVerifyIpLimit(servletRequest);
            accountService.resendVerification(request == null ? null : request.getEmail());
            return Response.<Void>builder().code(SUCCESS).info("成功").build();
        } catch (RateLimitExceededException e) {
            return rateLimited(e);
        } catch (Exception e) {
            log.error("resend-verification failed", e);
            // Still return generic success — we never leak whether the address exists.
            return Response.<Void>builder().code(SUCCESS).info("成功").build();
        }
    }

    public Response<Void> resendVerification(ResendVerificationRequestDTO request) {
        return resendVerification(request, null);
    }

    @PostMapping("/password-reset/request")
    public Response<Void> requestPasswordReset(@RequestBody PasswordResetRequestDTO request,
                                               HttpServletRequest servletRequest) {
        try {
            enforcePasswordResetIpLimit(servletRequest);
            accountService.requestPasswordReset(request == null ? null : request.getEmail());
        } catch (RateLimitExceededException e) {
            return rateLimited(e);
        } catch (Exception e) {
            log.error("password-reset request failed", e);
        }
        // Generic success for every input so the endpoint is not an email-enumeration oracle.
        return Response.<Void>builder().code(SUCCESS).info("成功").build();
    }

    public Response<Void> requestPasswordReset(PasswordResetRequestDTO request) {
        return requestPasswordReset(request, null);
    }

    @PostMapping("/password-reset/confirm")
    public Response<PasswordResetConfirmResponseDTO> confirmPasswordReset(
            @RequestBody PasswordResetConfirmRequestDTO request) {
        try {
            PasswordResetResult result = accountService.resetPassword(
                    request == null ? null : request.getToken(),
                    request == null ? null : request.getPassword());
            return Response.<PasswordResetConfirmResponseDTO>builder()
                    .code(SUCCESS).info("成功")
                    .data(PasswordResetConfirmResponseDTO.builder().status(result.name()).build())
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.<PasswordResetConfirmResponseDTO>builder()
                    .code(FAILURE).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("password-reset confirm failed", e);
            return Response.<PasswordResetConfirmResponseDTO>builder()
                    .code(FAILURE).info("password reset failed").build();
        }
    }

    @PostMapping("/login")
    public Response<LoginResponseDTO> login(@RequestBody LoginRequestDTO request,
                                            HttpServletRequest servletRequest,
                                            HttpServletResponse servletResponse) {
        LoginResult result;
        try {
            enforceLoginIpLimit(servletRequest);
            result = accountService.login(LoginAccountCommand.builder()
                    .email(request == null ? null : request.getEmail())
                    .rawPassword(request == null ? null : request.getPassword())
                    .build());
        } catch (RateLimitExceededException e) {
            return rateLimited(e);
        } catch (Exception e) {
            log.error("login failed", e);
            return Response.<LoginResponseDTO>builder().code(FAILURE).info("login failed").build();
        }
        LoginResponseDTO body = LoginResponseDTO.builder().status(result.getOutcome().name()).build();
        if (result.isSuccess()) {
            UserAccount user = result.getUser();
            establishSession(user, servletRequest, servletResponse);
            body.setUserId(user.getId());
            body.setEmail(user.getEmail());
            body.setAccountStatus(user.getStatus().name());
        }
        return Response.<LoginResponseDTO>builder().code(SUCCESS).info("成功").data(body).build();
    }

    @PostMapping("/logout")
    public Response<Void> logout(HttpServletRequest servletRequest) {
        HttpSession session = servletRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return Response.<Void>builder().code(SUCCESS).info("成功").build();
    }

    public Response<LoginResponseDTO> me() {
        return me(null);
    }

    @GetMapping("/me")
    public Response<LoginResponseDTO> me(HttpServletRequest servletRequest) {
        Optional<UserAccount> currentUser = currentSessionUser(servletRequest);
        if (currentUser.isEmpty()) {
            return Response.<LoginResponseDTO>builder()
                    .code(SUCCESS).info("成功")
                    .data(LoginResponseDTO.builder().status("ANONYMOUS").build())
                    .build();
        }
        UserAccount user = currentUser.get();
        return Response.<LoginResponseDTO>builder()
                .code(SUCCESS).info("成功")
                .data(LoginResponseDTO.builder()
                        .status(LoginResult.Outcome.SUCCESS.name())
                        .userId(user.getId())
                        .email(user.getEmail())
                        .accountStatus(user.getStatus().name())
                        .build())
                .build();
    }

    private void establishSession(UserAccount user, HttpServletRequest request, HttpServletResponse response) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedSessionUser(user.getId(), user.getSessionVersion()), null, List.of(ROLE_USER));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private void enforceRegisterVerifyIpLimit(HttpServletRequest request) {
        usageCounterRateLimiter.consume(
                REGISTER_VERIFY_IP_BUCKET,
                clientIp(request),
                "Too many registration or verification email requests. Please try again later.",
                REGISTER_VERIFY_IP_HOURLY,
                REGISTER_VERIFY_IP_DAILY);
    }

    private void enforcePasswordResetIpLimit(HttpServletRequest request) {
        usageCounterRateLimiter.consume(
                PASSWORD_RESET_IP_BUCKET,
                clientIp(request),
                "Too many password-reset requests. Please try again later.",
                PASSWORD_RESET_IP_HOURLY);
    }

    private void enforceLoginIpLimit(HttpServletRequest request) {
        usageCounterRateLimiter.consume(
                LOGIN_IP_BUCKET,
                clientIp(request),
                "Too many sign-in attempts. Please try again later.",
                LOGIN_IP_HOURLY);
    }

    private <T> Response<T> rateLimited(RateLimitExceededException e) {
        return Response.<T>builder()
                .code(ResponseCode.AUTH_RATE_LIMITED.getCode())
                .info(e.getInfo() == null ? ResponseCode.AUTH_RATE_LIMITED.getInfo() : e.getInfo())
                .build();
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        // Trust the servlet container's resolved remote address; configure a trusted proxy filter upstream if needed.
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr.trim();
    }

    private Optional<UserAccount> currentSessionUser(HttpServletRequest servletRequest) {
        String userId = AuthenticatedUserPrincipal.currentUserId();
        Integer sessionVersion = AuthenticatedUserPrincipal.currentSessionVersion();
        if (userId == null) {
            return Optional.empty();
        }
        Optional<UserAccount> user = accountService.findById(userId);
        if (user.isPresent() && user.get().isActive()
                && sessionVersion != null && sessionVersion == user.get().getSessionVersion()) {
            return user;
        }
        clearStaleSession(servletRequest);
        return Optional.empty();
    }

    private void clearStaleSession(HttpServletRequest servletRequest) {
        HttpSession session = servletRequest == null ? null : servletRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
