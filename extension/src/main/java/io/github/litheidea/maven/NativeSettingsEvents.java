package io.github.litheidea.maven;

import java.util.ArrayList;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.bridge.MavenRepositorySystem;

/**
 * Observes Maven's original settings build before CLI options populate the
 * execution request. Captures exact source revisions rather than re-running
 * a settings builder or deriving a second configuration precedence.
 */
@Named("lithe-native-settings")
@Singleton
public final class NativeSettingsEvents extends AbstractEventSpy {
    private SettingsExport.Capture capture;
    private Exception failure;
    private boolean configurationExported;
    private final MavenRepositorySystem repositories;

    @Inject
    public NativeSettingsEvents(MavenRepositorySystem repositories) { this.repositories = repositories; }

    @Override
    public void onEvent(Object event) {
        try {
            if (event instanceof SettingsBuildingRequest) {
                SettingsBuildingRequest request = (SettingsBuildingRequest) event;
                if (!requested(request)) return;
                capture = new SettingsExport.Capture(request);
            } else if (event instanceof SettingsBuildingResult && capture != null) {
                capture.effectiveProfiles = new ArrayList<>(
                        ((SettingsBuildingResult) event).getEffectiveSettings().getActiveProfiles());
            } else if (event instanceof MavenExecutionRequest) {
                MavenExecutionRequest request = (MavenExecutionRequest) event;
                String output = request.getUserProperties().getProperty("lithe.configurationOutput",
                        request.getSystemProperties().getProperty("lithe.configurationOutput"));
                if (output == null || output.trim().isEmpty()) return;
                // MavenCli publishes this fully populated request before
                // DefaultMaven creates its repository session. Preserve the
                // upstream-selected facts, then isolate the probe's repository
                // manager so merely reading settings cannot create the user's
                // actual repository. Normal model/build requests never do this.
                MavenExecutionRequest selected = DefaultMavenExecutionRequest.copy(request);
                Path privateRepository = Paths.get(output).toAbsolutePath().getParent().resolve("repository");
                request.setLocalRepository(null);
                request.setLocalRepositoryPath(privateRepository.toFile());
                request.setLocalRepository(repositories.createLocalRepository(request, privateRepository.toFile()));
                LitheLifecycleParticipant.write(output, Json.write(SettingsExport.document(selected, capture())));
                configurationExported = true;
            }
        } catch (Exception error) { failure = error; }
    }

    SettingsExport.Capture capture() throws Exception {
        if (failure != null) throw failure;
        return capture;
    }

    void requireConfigurationExported() throws Exception {
        if (failure != null) throw failure;
        if (!configurationExported) throw new IllegalStateException("Maven did not publish its populated execution request.");
    }

    private static boolean requested(SettingsBuildingRequest request) {
        for (String key : new String[] {"lithe.configurationOutput", "lithe.configurationContextOutput"}) {
            if (request.getUserProperties().containsKey(key) || request.getSystemProperties().containsKey(key)) return true;
        }
        return false;
    }
}
