package io.github.essandhu.ledger.console.web;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The M8a demo surface: who is logged in, with which realm roles. The role chips are the
 * no-hierarchy model made visible — {@code ops} shows the {@code CONSOLE_OPS} composite
 * next to the three roles it expands to; {@code viewer} shows exactly one chip.
 */
@Controller
class WhoamiController {

    @GetMapping("/")
    String index() {
        return "redirect:/whoami";
    }

    @GetMapping("/whoami")
    String whoami(OAuth2AuthenticationToken authentication, Model model) {
        OidcUser user = (OidcUser) authentication.getPrincipal();
        model.addAttribute("username", user.getPreferredUsername());
        model.addAttribute("fullName", user.getFullName());
        // Chips render the MAPPED authorities (ConsoleRealmRoleMapper output), not raw
        // claims: what the page shows is exactly what sec:authorize decides on.
        model.addAttribute("roles", authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .sorted()
                .toList());
        return "whoami";
    }
}
