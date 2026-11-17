package org.codelabuk.perf.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

/** The shipped manifests parse, and the example LoadTest is a valid spec that produces sensible Jobs. */
class ManifestsTest {

    private static List<String> docs(String file) throws Exception {
        String text = Files.readString(Path.of("deploy", file));
        return Arrays.stream(text.split("(?m)^---\\s*$")).map(String::strip)
                .filter(d -> d.lines().anyMatch(l -> l.startsWith("kind:"))).toList();
    }

    @Test
    void operatorManifestParses() throws Exception {
        List<String> kinds = docs("operator.yaml").stream()
                .map(d -> Serialization.unmarshal(d, GenericKubernetesResource.class).getKind()).toList();
        assertEquals(List.of("Namespace", "ServiceAccount", "ClusterRole", "ClusterRoleBinding", "Role", "RoleBinding", "Deployment"), kinds);
    }

    @Test
    void exampleLoadTestIsValid() throws Exception {
        String doc = docs("example-loadtest.yaml").stream().filter(d -> d.contains("kind: LoadTest")).findFirst().orElseThrow();
        LoadTest lt = Serialization.unmarshal(doc, LoadTest.class);
        assertNull(JobFactory.specProblem(lt.getSpec()));
        assertEquals(5, lt.getSpec().workers);
        assertEquals("plans/sr-email-status.yaml", lt.getSpec().plan.file);
        lt.setMetadata(new ObjectMetaBuilder(lt.getMetadata()).withUid("abcdef12-0000").build());
        Job workers = JobFactory.workers(lt, JobFactory.runDir(lt), "2026-11-20T10:01:00Z", true);
        assertEquals("4Gi", workers.getSpec().getTemplate().getSpec().getContainers().get(0).getResources().getLimits().get("memory").toString());
        assertEquals("sr-flow-run-1-workers", workers.getMetadata().getName());
    }
}
