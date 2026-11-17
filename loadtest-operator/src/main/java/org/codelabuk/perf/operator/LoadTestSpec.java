package org.codelabuk.perf.operator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Required;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Toleration;

public class LoadTestSpec {

    @Required
    @JsonPropertyDescription("perf runner image, e.g. registry/labuk-perf-runner:1.0.0")
    public String image;

    @Required
    @JsonPropertyDescription("Where the test plan comes from and which plan file to run")
    public Plan plan;

    @Min(1)
    @JsonPropertyDescription("Number of worker pods; all load figures in the plan are totals split across them")
    public int workers = 1;

    @JsonPropertyDescription("Run only these scenarios of the plan (default: all)")
    public List<String> scenarios = new ArrayList<>();

    @JsonPropertyDescription("Environment variables for every pod - fill ${NAME} placeholders in the plan")
    public Map<String, String> env = new LinkedHashMap<>();

    @JsonPropertyDescription("Secrets whose keys become environment variables (passwords, tokens) - never put them in env")
    public List<String> secretRefs = new ArrayList<>();

    @JsonPropertyDescription("CPU/memory for each worker; the JVM heap is 75% of the memory limit")
    public ResourceRequirements resources;

    public Map<String, String> nodeSelector = new LinkedHashMap<>();

    public List<Toleration> tolerations = new ArrayList<>();

    @Required
    @JsonPropertyDescription("Where results and the merged report are written")
    public Results results;

    @Min(5)
    @JsonPropertyDescription("Seconds between creating the workers and their common start time (image pulls must fit in it)")
    public int startDelaySeconds = 60;

    @Min(1)
    @JsonPropertyDescription("Hard limit for the worker phase, in minutes")
    public int timeoutMinutes = 180;

    @JsonPropertyDescription("Seconds to keep finished Jobs and pods (logs) before Kubernetes deletes them")
    public int ttlSecondsAfterFinished = 3600;

    public static class Plan {
        @Required
        @JsonPropertyDescription("Plan file: relative to the repository / ConfigMap root, or an absolute path inside the image")
        public String file;

        @JsonPropertyDescription("Clone the tests from Git (recommended)")
        public Git git;

        @JsonPropertyDescription("Mount a ConfigMap as the tests folder (small, flat test sets - 1 MB limit)")
        public String configMap;

        @JsonPropertyDescription("The tests are baked into the image; `file` is an absolute path")
        public boolean inImage;
    }

    public static class Git {
        @Required
        public String repo;

        public String ref = "main";

        @JsonPropertyDescription("Secret with the access token for an https repository")
        public SecretKey tokenSecret;

        @JsonPropertyDescription("Image used to clone")
        public String image = "alpine/git:2.47.2";
    }

    public static class SecretKey {
        @Required
        public String name;

        public String key = "token";
    }

    public static class Results {
        @Required
        @JsonPropertyDescription("Existing PersistentVolumeClaim shared by all pods of the run (ReadWriteMany on multi-node clusters)")
        public String pvc;
    }
}
