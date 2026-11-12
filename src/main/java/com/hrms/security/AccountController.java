package com.hrms.security;

import com.hrms.exception.BusinessException;
import com.hrms.exception.ResourceNotFoundException;
import com.hrms.security.dto.ChangePasswordDto;
import com.hrms.security.entity.User;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for authenticated account self-service endpoints (/account/password) (GAP-03).
 */
@Controller
@RequestMapping("/account")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/password")
    public String passwordForm(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        User user = accountService.getUserById(principal.getUserId());
        model.addAttribute("passwordForm", new ChangePasswordDto());
        model.addAttribute("passwordResetAt", user.getPasswordResetAt());
        model.addAttribute("accountEmail", user.getEmail());
        return "account/password";
    }

    @PostMapping("/password")
    public String updatePassword(@AuthenticationPrincipal CustomUserDetails principal,
                                 @Valid @ModelAttribute("passwordForm") ChangePasswordDto formDto,
                                 BindingResult bindingResult,
                                 Model model,
                                 RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            User user = accountService.getUserById(principal.getUserId());
            model.addAttribute("passwordResetAt", user.getPasswordResetAt());
            model.addAttribute("accountEmail", user.getEmail());
            return "account/password";
        }

        try {
            accountService.changePassword(
                    principal.getUserId(),
                    formDto.getCurrentPassword(),
                    formDto.getNewPassword(),
                    formDto.getConfirmPassword()
            );
            redirectAttributes.addFlashAttribute("successMessage",
                    "Your password has been updated and recorded in the audit trail.");
            return "redirect:/account/password";
        } catch (BusinessException | ResourceNotFoundException ex) {
            User user = accountService.getUserById(principal.getUserId());
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("passwordResetAt", user.getPasswordResetAt());
            model.addAttribute("accountEmail", user.getEmail());
            return "account/password";
        } catch (Exception ex) {
            log.error("Unexpected error changing password for userId={}", principal.getUserId(), ex);
            User user = accountService.getUserById(principal.getUserId());
            model.addAttribute("errorMessage", "Unable to update password due to an unexpected error.");
            model.addAttribute("passwordResetAt", user.getPasswordResetAt());
            model.addAttribute("accountEmail", user.getEmail());
            return "account/password";
        }
    }
}
