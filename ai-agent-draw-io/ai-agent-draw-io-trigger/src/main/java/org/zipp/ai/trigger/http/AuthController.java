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
import org.zipp.ai.api.dto.RegisterAccountRequestDTO;
import org.zipp.ai.api.dto.RegisterAccountResponseDTO;
import org.zipp.ai.api.dto.ResendVerificationRequestDTO;
import org.zipp.ai.api.dto.VerifyEmailResponseDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.EmailVerificationResult;
import org.zipp.ai.domain.account.model.valobj.LoginAccountCommand;
import org.zipp.ai.domain.account.model.valobj.LoginResult;
import org.zipp.ai.domain.account.model.valobj.RegisterAccountCommand;
import org.zipp.ai.domain.account.service.IAccountService;

import javax.annotation.Resource;
import java.util.List;

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

    @Resource
    private IAccountService accountService;

    @Resource
    private SecurityContextRepository securityContextRepository;

    @PostMapping("/register")
    public Response<RegisterAccountResponseDTO> register(@RequestBody RegisterAccountRequestDTO request) {
        try {
            accountService.register(RegisterAccountCommand.builder()
                    .email(request == null ? null : request.getEmail())
                    .rawPassword(request == null ? null : request.getPassword())
                    .build());
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
    public Response<Void> resendVerification(@RequestBody ResendVerificationRequestDTO request) {
        try {
            accountService.resendVerification(request == null ? null : request.getEmail());
            return Response.<Void>builder().code(SUCCESS).info("成功").build();
        } catch (Exception e) {
            log.error("resend-verification failed", e);
            // Still return generic success — we never leak whether the address exists.
            return Response.<Void>builder().code(SUCCESS).info("成功").build();
        }
    }

    @PostMapping("/login")
    public Response<LoginResponseDTO> login(@RequestBody LoginRequestDTO request,
                                            HttpServletRequest servletRequest,
                                            HttpServletResponse servletResponse) {
        LoginResult result;
        try {
            result = accountService.login(LoginAccountCommand.builder()
                    .email(request == null ? null : request.getEmail())
                    .rawPassword(request == null ? null : request.getPassword())
                    .build());
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

    @GetMapping("/me")
    public Response<LoginResponseDTO> me() {
        String userId = org.zipp.ai.trigger.http.service.AuthenticatedUserPrincipal.currentUserId();
        if (userId == null) {
            return Response.<LoginResponseDTO>builder()
                    .code(SUCCESS).info("成功")
                    .data(LoginResponseDTO.builder().status("ANONYMOUS").build())
                    .build();
        }
        return accountService.findById(userId)
                .map(user -> Response.<LoginResponseDTO>builder()
                        .code(SUCCESS).info("成功")
                        .data(LoginResponseDTO.builder()
                                .status(LoginResult.Outcome.SUCCESS.name())
                                .userId(user.getId())
                                .email(user.getEmail())
                                .accountStatus(user.getStatus().name())
                                .build())
                        .build())
                .orElseGet(() -> Response.<LoginResponseDTO>builder()
                        .code(SUCCESS).info("成功")
                        .data(LoginResponseDTO.builder().status("ANONYMOUS").build())
                        .build());
    }

    private void establishSession(UserAccount user, HttpServletRequest request, HttpServletResponse response) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user.getId(), null, List.of(ROLE_USER));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
