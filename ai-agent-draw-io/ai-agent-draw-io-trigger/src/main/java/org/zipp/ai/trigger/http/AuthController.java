package org.zipp.ai.trigger.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.api.dto.RegisterAccountRequestDTO;
import org.zipp.ai.api.dto.RegisterAccountResponseDTO;
import org.zipp.ai.api.dto.ResendVerificationRequestDTO;
import org.zipp.ai.api.dto.VerifyEmailResponseDTO;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.valobj.EmailVerificationResult;
import org.zipp.ai.domain.account.model.valobj.RegisterAccountCommand;
import org.zipp.ai.domain.account.service.IAccountService;

import javax.annotation.Resource;

/**
 * Registration and email-verification endpoints. Login/logout land with #3 and are intentionally
 * absent from this controller — separating them keeps the auth surface small until Spring Security
 * session handling is introduced.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private static final String SUCCESS = "0000";
    private static final String FAILURE = "0001";

    @Resource
    private IAccountService accountService;

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
}
