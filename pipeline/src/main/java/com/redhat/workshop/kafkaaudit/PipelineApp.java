package com.redhat.workshop.kafkaaudit;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import com.redhat.workshop.kafkaaudit.config.PipelineConfig;
import com.redhat.workshop.kafkaaudit.health.RoleState;
import com.redhat.workshop.kafkaaudit.role.ProcessorRunner;
import com.redhat.workshop.kafkaaudit.role.Role;
import com.redhat.workshop.kafkaaudit.role.RoleRunner;
import com.redhat.workshop.kafkaaudit.role.SinkRunner;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/// Arranca el rol que corresponde a esta instancia y lo detiene ordenadamente al recibir SIGTERM.
@ApplicationScoped
public class PipelineApp {

    private static final Logger LOG = Logger.getLogger(PipelineApp.class);

    private final PipelineConfig config;

    /// Hilo gestionado por el runtime: un `new Thread()` no participa del cierre de Quarkus.
    private final ManagedExecutor executor;

    /// Lo que publica la probe de liveness: refleja si el bucle del rol sigue en marcha.
    private final RoleState state;

    /// `Instance` y no el bean directo: solo se materializa el rol de esta instancia, así el
    /// processor no construye el consumidor del sink solo por estar en el mismo binario.
    private final Instance<ProcessorRunner> processor;
    private final Instance<SinkRunner> sink;

    private volatile RoleRunner runner;

    @Inject
    public PipelineApp(PipelineConfig config, ManagedExecutor executor, RoleState state,
                       Instance<ProcessorRunner> processor, Instance<SinkRunner> sink) {
        this.config = config;
        this.executor = executor;
        this.state = state;
        this.processor = processor;
        this.sink = sink;
    }

    void onStart(@Observes StartupEvent event) {
        Role role = Role.from(config.role());
        runner = switch (role) {
            case PROCESSOR -> processor.get();
            case SINK -> sink.get();
        };
        LOG.infof("iniciando rol %s contra %s", role.name().toLowerCase(), config.bootstrap());
        state.started(role.name().toLowerCase());

        executor.runAsync(() -> {
            try {
                runner.run();
            } catch (RuntimeException e) {
                // Un fallo permanente debe tumbar el pod para que se vea el CrashLoop; si solo se
                // registra, el contenedor sigue "sano" sin procesar nada.
                LOG.fatal("el rol terminó con error, se cierra la aplicación", e);
                Quarkus.asyncExit(1);
            } finally {
                // Marca la liveness como caída pase lo que pase: si el bucle sale, el pod no está sano.
                state.stopped();
            }
        });
    }

    void onStop(@Observes ShutdownEvent event) {
        if (runner != null) {
            runner.stop();
        }
    }
}
