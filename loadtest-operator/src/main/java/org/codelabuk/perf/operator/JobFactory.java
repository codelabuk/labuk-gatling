package org.codelabuk.perf.operator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.EnvFromSourceBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;

/**
 * Builds the four Jobs of a run. Every pod runs the perf image with the same tests (/tests), the same results
 * volume (/results) and the same environment, so a plan behaves identically in each phase.
 */
public final class JobFactory {

    public enum Role { validate, setup, workers, report }

    public static final String LABEL_LOADTEST = "perf.codelabuk.org/loadtest";
    public static final String LABEL_ROLE = "perf.codelabuk.org/role";
    static final String TESTS = "/tests";
    static final String RESULTS = "/results";

    private JobFactory() {}

    /** Job name for a role - kept short because Kubernetes copies it into a 63-character pod label. */
    public static String jobName(LoadTest lt, Role role) {
        String base = lt.getMetadata().getName();
        if (base.length() > 45) base = base.substring(0, 45);
        return base + "-" + role.name();
    }

    /** Run folder on the results volume: unique per LoadTest, readable by people. */
    public static String runDir(LoadTest lt) {
        String uid = lt.getMetadata().getUid() == null ? "local" : lt.getMetadata().getUid().replace("-", "");
        return RESULTS + "/" + lt.getMetadata().getName() + "-" + uid.substring(0, Math.min(8, uid.length()));
    }

    public static String planPath(LoadTestSpec spec) {
        return spec.plan.inImage || spec.plan.file.startsWith("/") ? spec.plan.file : TESTS + "/" + spec.plan.file;
    }

    /** Problems in the spec itself, before anything is created; null when fine. */
    public static String specProblem(LoadTestSpec spec) {
        if (spec == null) return "spec is missing";
        if (spec.image == null || spec.image.isBlank()) return "spec.image is required";
        if (spec.plan == null || spec.plan.file == null || spec.plan.file.isBlank()) return "spec.plan.file is required";
        int sources = (spec.plan.git != null ? 1 : 0) + (spec.plan.configMap != null ? 1 : 0) + (spec.plan.inImage ? 1 : 0);
        if (sources != 1) return "spec.plan needs exactly one of: git, configMap, inImage";
        if (spec.plan.inImage && !spec.plan.file.startsWith("/")) return "spec.plan.file must be an absolute path when inImage is true";
        if (spec.workers < 1) return "spec.workers must be at least 1";
        if (spec.results == null || spec.results.pvc == null || spec.results.pvc.isBlank()) return "spec.results.pvc is required";
        return null;
    }

    public static Job validate(LoadTest lt) {
        List<String> args = new ArrayList<>(List.of("validate", planPath(lt.getSpec()),
                "--workers", String.valueOf(lt.getSpec().workers), "--facts-file", "/dev/termination-log"));
        scenarioArgs(lt.getSpec(), args);
        return job(lt, Role.validate, args, Map.of(), 1);
    }

    public static Job setup(LoadTest lt, String runDir) {
        return job(lt, Role.setup, List.of("setup", planPath(lt.getSpec()), "--out", runDir + "/shared.json"), Map.of(), 1);
    }

    /** An Indexed Job: Kubernetes gives each pod JOB_COMPLETION_INDEX, which perf uses as its worker number. */
    public static Job workers(LoadTest lt, String runDir, String startAt, boolean sharedIn) {
        LoadTestSpec spec = lt.getSpec();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PLAN", planPath(spec));
        env.put("WORKER_COUNT", String.valueOf(spec.workers));
        env.put("START_AT", startAt);
        env.put("RESULTS_DIR", runDir + "/logs");
        if (sharedIn) env.put("SHARED_IN", runDir + "/shared.json");
        if (!spec.scenarios.isEmpty()) env.put("SCENARIO", String.join(",", spec.scenarios));
        boolean metrics = spec.metrics != null && spec.metrics.enabled;
        if (metrics) {
            env.put("METRICS_PORT", String.valueOf(spec.metrics.port));
            env.put("METRICS_LINGER_SECONDS", String.valueOf(spec.metrics.lingerSeconds));
        }
        Job job = job(lt, Role.workers, List.of("worker"), env, spec.workers);
        job.getSpec().setCompletionMode("Indexed");
        job.getSpec().setActiveDeadlineSeconds((long) spec.timeoutMinutes * 60 + spec.startDelaySeconds
                + (metrics ? spec.metrics.lingerSeconds : 0));
        Container perf = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        perf.setResources(spec.resources);
        if (metrics) {
            perf.setPorts(List.of(new io.fabric8.kubernetes.api.model.ContainerPortBuilder()
                    .withName("metrics").withContainerPort(spec.metrics.port).build()));
            // the de-facto standard annotations; a PodMonitor can select the role label instead
            job.getSpec().getTemplate().getMetadata().setAnnotations(Map.of(
                    "prometheus.io/scrape", "true",
                    "prometheus.io/port", String.valueOf(spec.metrics.port),
                    "prometheus.io/path", "/metrics"));
        }
        return job;
    }

    public static Job report(LoadTest lt, String runDir) {
        return job(lt, Role.report, List.of("report", "--input", runDir + "/logs", "--output", runDir + "/report",
                "--name", "merged", "--facts-file", "/dev/termination-log"), Map.of(), 1);
    }

    private static void scenarioArgs(LoadTestSpec spec, List<String> args) {
        if (!spec.scenarios.isEmpty()) {
            args.add("--scenario");
            args.add(String.join(",", spec.scenarios));
        }
    }

    private static Job job(LoadTest lt, Role role, List<String> args, Map<String, String> extraEnv, int pods) {
        LoadTestSpec spec = lt.getSpec();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/managed-by", "loadtest-operator");
        labels.put(LABEL_LOADTEST, lt.getMetadata().getName());
        labels.put(LABEL_ROLE, role.name());

        List<EnvVar> env = new ArrayList<>();
        spec.env.forEach((k, v) -> env.add(new EnvVarBuilder().withName(k).withValue(v).build()));
        extraEnv.forEach((k, v) -> env.add(new EnvVarBuilder().withName(k).withValue(v).build()));
        List<EnvFromSource> envFrom = new ArrayList<>();
        spec.secretRefs.forEach(s -> envFrom.add(new EnvFromSourceBuilder().withNewSecretRef().withName(s).endSecretRef().build()));

        List<Volume> volumes = new ArrayList<>();
        List<VolumeMount> mounts = new ArrayList<>();
        volumes.add(new VolumeBuilder().withName("results").withNewPersistentVolumeClaim().withClaimName(spec.results.pvc).endPersistentVolumeClaim().build());
        mounts.add(new VolumeMountBuilder().withName("results").withMountPath(RESULTS).build());
        List<Container> init = new ArrayList<>();
        if (spec.plan.git != null) {
            volumes.add(new VolumeBuilder().withName("tests").withNewEmptyDir().endEmptyDir().build());
            init.add(gitClone(spec.plan.git));
        } else if (spec.plan.configMap != null) {
            volumes.add(new VolumeBuilder().withName("tests").withNewConfigMap().withName(spec.plan.configMap).endConfigMap().build());
        }
        if (!spec.plan.inImage) mounts.add(new VolumeMountBuilder().withName("tests").withMountPath(TESTS).withReadOnly(spec.plan.git == null).build());

        Container perf = new ContainerBuilder()
                .withName("perf")
                .withImage(spec.image)
                .withArgs(args)
                .withEnv(env)
                .withEnvFrom(envFrom)
                .withVolumeMounts(mounts)
                .withTerminationMessagePolicy("FallbackToLogsOnError")
                .build();

        return new JobBuilder()
                .withNewMetadata()
                    .withName(jobName(lt, role))
                    .withNamespace(lt.getMetadata().getNamespace())
                    .withLabels(labels)
                    .withOwnerReferences(new OwnerReferenceBuilder()
                            .withApiVersion(lt.getApiVersion()).withKind(lt.getKind())
                            .withName(lt.getMetadata().getName()).withUid(lt.getMetadata().getUid())
                            .withController(true).withBlockOwnerDeletion(true).build())
                .endMetadata()
                .withNewSpec()
                    .withCompletions(pods)
                    .withParallelism(pods)
                    .withBackoffLimit(0) // a retried worker would start late and skew the test - fail instead
                    .withTtlSecondsAfterFinished(spec.ttlSecondsAfterFinished)
                    .withNewTemplate()
                        .withNewMetadata().withLabels(labels).endMetadata()
                        .withSpec(new PodSpecBuilder()
                                .withRestartPolicy("Never")
                                .withInitContainers(init)
                                .withContainers(perf)
                                .withVolumes(volumes)
                                .withNodeSelector(spec.nodeSelector)
                                .withTolerations(spec.tolerations)
                                .withNewSecurityContext().withRunAsNonRoot(true).withRunAsUser(1000L).withFsGroup(1000L).endSecurityContext()
                                .build())
                    .endTemplate()
                .endSpec()
                .build();
    }

    private static Container gitClone(LoadTestSpec.Git git) {
        List<EnvVar> env = new ArrayList<>(List.of(
                new EnvVarBuilder().withName("GIT_REPO").withValue(git.repo).build(),
                new EnvVarBuilder().withName("GIT_REF").withValue(git.ref).build(),
                new EnvVarBuilder().withName("HOME").withValue("/tmp").build()));
        if (git.tokenSecret != null)
            env.add(new EnvVarBuilder().withName("GIT_TOKEN").withNewValueFrom().withNewSecretKeyRef()
                    .withName(git.tokenSecret.name).withKey(git.tokenSecret.key).endSecretKeyRef().endValueFrom().build());
        // the token is passed via env, never written into the spec or logs
        String script = "set -e; URL=\"$GIT_REPO\"; "
                + "if [ -n \"$GIT_TOKEN\" ]; then URL=$(echo \"$GIT_REPO\" | sed \"s#https://#https://oauth2:${GIT_TOKEN}@#\"); fi; "
                + "git clone --quiet --depth 1 --branch \"$GIT_REF\" \"$URL\" " + TESTS + "; echo \"cloned $GIT_REPO@$GIT_REF\"";
        return new ContainerBuilder()
                .withName("clone-tests")
                .withImage(git.image)
                .withCommand("sh", "-c", script)
                .withEnv(env)
                .withVolumeMounts(new VolumeMountBuilder().withName("tests").withMountPath(TESTS).build())
                .build();
    }
}
