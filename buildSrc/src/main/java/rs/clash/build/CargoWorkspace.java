package rs.clash.build;

import groovy.json.JsonSlurper;
import java.io.File;
import java.util.Map;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;

public final class CargoWorkspace {
    private CargoWorkspace() {}

    public static Provider<File> targetDirectory(ProviderFactory providers, Directory workspace) {
        return providers.exec(exec -> {
            exec.workingDir(workspace);
            exec.commandLine("cargo", "metadata", "--format-version=1", "--no-deps");
        }).getStandardOutput().getAsText().map(json -> {
            var metadata = (Map<?, ?>) new JsonSlurper().parseText(json);
            return new File((String) metadata.get("target_directory"));
        });
    }
}
