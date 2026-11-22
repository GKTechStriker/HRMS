package com.hrms.security;

import com.hrms.exception.BusinessException;
import com.hrms.security.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Controller managing self-service password recovery workflows.
 */
@Controller
public class PasswordRecoveryController {

    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryController.class);

    private final PasswordRecoveryService passwordRecoveryService;

    public PasswordRecoveryController(PasswordRecoveryService passwordRecoveryService) {
        this.passwordRecoveryService = passwordRecoveryService;
    }

    @GetMapping("/forgot-password")
    public String showForgotPasswordForm() {
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String handleForgotPassword(@RequestParam("email") String email,
                                       HttpServletRequest request,
                                       RedirectAttributes redirectAttributes) {
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(request.getContextPath())
                .build()
                .toUriString();

        try {
            passwordRecoveryService.requestPasswordReset(email, baseUrl);
        } catch (Exception ex) {
            log.warn("Error processing password reset for email '{}': {}", email, ex.getMessage());
        }

        // Always show uniform confirmation to protect against email enumeration attacks
        redirectAttributes.addFlashAttribute("infoMessage",
                "If an account with that email exists, password reset instructions have been dispatched.");
        return "redirect:/forgot-password?sent=true";
    }

    @GetMapping("/reset-password")
    public String showResetPasswordForm(@RequestParam(value = "token", required = false) String token,
                                        Model model,
                                        RedirectAttributes redirectAttributes) {
        if (token == null || token.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Password reset token is missing.");
            return "redirect:/forgot-password";
        }

        try {
            User user = passwordRecoveryService.validateResetToken(token);
            model.addAttribute("token", token);
            model.addAttribute("email", user.getEmail());
            return "auth/reset-password";
        } catch (BusinessException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/forgot-password?invalid=true";
        }
    }

    @PostMapping("/reset-password")
    public String handleResetPassword(@RequestParam("token") String token,
                                      @RequestParam("newPassword") String newPassword,
                                      @RequestParam("confirmPassword") String confirmPassword,
                                      RedirectAttributes redirectAttributes) {
        try {
            passwordRecoveryService.resetPassword(token, newPassword, confirmPassword);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Your password has been successfully reset. Please sign in with your new credentials.");
            return "redirect:/login?resetSuccess=true";
        } catch (BusinessException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/reset-password?token=" + token;
        }
    }
}
