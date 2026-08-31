package com.redhat.workshop.kafkaaudit;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import com.redhat.workshop.kafkaaudit.config.PipelineConfig;
import com.redhat.workshop.kafkaaudit.health.RoleState;
import com.redhat.workshop.kafkaaudit.role.DummyDataRunner;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/// Arranque del generador de datos ficticios. No tiene roles: esta aplicación solo hace una cosa.
///
/// Vive en su propio artefacto, y no como un rol más del pipeline, para que la imagen de producción
/// no contenga el código que inventa datos personales.
@ApplicationScoped
public class DummyDataApp {

    private static final Logger LOG = Logger.getLogger(DummyDataApp.class);

    private static final String ROLE = "dummy-data";

    private final PipelineConfig config;

    /// Hilo gestionado por el runtime: un `new Thread()` no participa del cierre de Quarkus.
    private final ManagedExecutor executor;

    private final RoleState state;
    private final DummyDataRunner runner;

    @Inject
    public DummyDataApp(PipelineConfig config, ManagedExecutor executor, RoleState state,
                        DummyDataRunner runner) {
        this.config = config;
        this.executor = executor;
        this.state = state;
        this.runner = runner;
    }

    void onStart(@Observes StartupEvent event) {
        LOG.infof("emitiendo datos ficticios contra %s", config.bootstrap());
        state.started(ROLE);

        executor.runAsync(() -> {
            try {
                runner.run();
            } catch (RuntimeException e) {
                // Un fallo permanente debe tumbar el pod para que se vea el CrashLoop; si solo se
                // registra, el contenedor sigue "sano" sin emitir nada.
                LOG.fatal("el generador terminó con error, se cierra la aplicación", e);
                Quarkus.asyncExit(1);
            } finally {
                state.stopped();
            }
        });
    }

    void onStop(@Observes ShutdownEvent event) {
        runner.stop();
    }
}
