package com.gatekeeper.security.service;

import com.gatekeeper.security.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

@Component
@Profile("!foundation")
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private static final String BOOTSTRAP_OPTION = "gatekeeper.bootstrap.enabled";

    private final AdminUserRepository adminUserRepository;
    private final AdminBootstrapService adminBootstrapService;
    private final Environment environment;

    public AdminBootstrapRunner(AdminUserRepository adminUserRepository, AdminBootstrapService adminBootstrapService) {
        this(adminUserRepository, adminBootstrapService, null);
    }

    @Autowired
    public AdminBootstrapRunner(AdminUserRepository adminUserRepository,
                                AdminBootstrapService adminBootstrapService,
                                Environment environment) {
        this.adminUserRepository = adminUserRepository;
        this.adminBootstrapService = adminBootstrapService;
        this.environment = environment;
    }

    public boolean isBootstrapRequested(ApplicationArguments args) {
        if (args != null && args.containsOption(BOOTSTRAP_OPTION)) {
            List<String> values = args.getOptionValues(BOOTSTRAP_OPTION);
            if (values == null || values.isEmpty()) {
                // Flag passed without value: e.g. --gatekeeper.bootstrap.enabled
                return true;
            }
            return "true".equalsIgnoreCase(values.get(0));
        }
        if (environment != null) {
            String prop = environment.getProperty(BOOTSTRAP_OPTION);
            if (prop != null) {
                return "true".equalsIgnoreCase(prop) || prop.isBlank();
            }
        }
        return false;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isBootstrapRequested(args)) {
            return;
        }

        log.info("Explicit administrator bootstrap flag detected ('--{}'). Starting bootstrap verification...", BOOTSTRAP_OPTION);

        if (adminUserRepository.count() > 0) {
            log.info("Administrator bootstrap skipped: an administrator already exists in the database.");
            return;
        }

        Console console = System.console();
        String username;
        String email;
        char[] password;

        if (console != null) {
            System.out.println("\n=== Gatekeeper Initial Administrator Bootstrap ===");
            username = console.readLine("Enter initial administrator username: ");
            email = console.readLine("Enter initial administrator email: ");
            password = console.readPassword("Enter initial administrator password (min 12 chars, upper/lower/digit/special): ");
        } else {
            // Check if stdin has available input before falling back to environment
            String envUser = System.getenv("BOOTSTRAP_ADMIN_USERNAME");
            String envEmail = System.getenv("BOOTSTRAP_ADMIN_EMAIL");
            String envPass = System.getenv("BOOTSTRAP_ADMIN_PASSWORD");

            if (envUser != null && envEmail != null && envPass != null) {
                username = envUser;
                email = envEmail;
                password = envPass.toCharArray();
            } else {
                try {
                    Scanner scanner = new Scanner(System.in);
                    System.out.println("\n=== Gatekeeper Initial Administrator Bootstrap (Standard Input) ===");
                    System.out.print("Enter initial administrator username: ");
                    username = scanner.hasNextLine() ? scanner.nextLine() : null;
                    System.out.print("Enter initial administrator email: ");
                    email = scanner.hasNextLine() ? scanner.nextLine() : null;
                    System.out.print("Enter initial administrator password: ");
                    password = scanner.hasNextLine() ? scanner.nextLine().toCharArray() : null;
                } catch (Exception e) {
                    System.out.println("No interactive console, standard input, or BOOTSTRAP_ADMIN_* environment variables detected.");
                    return;
                }
            }
        }

        try {
            adminBootstrapService.bootstrapAdmin(username, email, password, "ROLE_SUPER_ADMIN");
            System.out.println("\n[SUCCESS] Initial administrator bootstrapped successfully.");
        } catch (Exception e) {
            System.err.println("\n[ERROR] Bootstrap failed: " + e.getMessage());
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }
}
