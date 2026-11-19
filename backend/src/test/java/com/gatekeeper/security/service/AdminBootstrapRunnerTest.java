package com.gatekeeper.security.service;

import com.gatekeeper.security.repository.AdminUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private AdminBootstrapService adminBootstrapService;

    @Test
    @DisplayName("Runner exits immediately when --gatekeeper.bootstrap.enabled is absent")
    void runnerExitsWhenFlagAbsent() {
        AdminBootstrapRunner runner = new AdminBootstrapRunner(adminUserRepository, adminBootstrapService);
        runner.run(new DefaultApplicationArguments());

        verify(adminUserRepository, never()).count();
        verify(adminBootstrapService, never()).bootstrapAdmin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Runner exits immediately when --gatekeeper.bootstrap.enabled is false")
    void runnerExitsWhenFlagFalse() {
        AdminBootstrapRunner runner = new AdminBootstrapRunner(adminUserRepository, adminBootstrapService);
        runner.run(new DefaultApplicationArguments("--gatekeeper.bootstrap.enabled=false"));

        verify(adminUserRepository, never()).count();
        verify(adminBootstrapService, never()).bootstrapAdmin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Runner skips bootstrap when an administrator already exists in database")
    void runnerSkipsWhenAdminAlreadyExists() {
        when(adminUserRepository.count()).thenReturn(1L);

        AdminBootstrapRunner runner = new AdminBootstrapRunner(adminUserRepository, adminBootstrapService);
        runner.run(new DefaultApplicationArguments("--gatekeeper.bootstrap.enabled=true"));

        verify(adminUserRepository).count();
        verify(adminBootstrapService, never()).bootstrapAdmin(any(), any(), any(), any());
    }
}
