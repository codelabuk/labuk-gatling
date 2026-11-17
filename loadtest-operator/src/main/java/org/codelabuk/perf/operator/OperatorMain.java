package org.codelabuk.perf.operator;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.config.LeaderElectionConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the operator.
 *   POD_NAMESPACE  set in the cluster (Helm chart / manifests): leader election through a Lease in that namespace,
 *                  so several replicas can run and only one works at a time
 *   KUBE_CONTEXT   local development: run against this kubeconfig context instead of the current one
 */
public final class OperatorMain {

    private static final Logger log = LoggerFactory.getLogger(OperatorMain.class);

    private OperatorMain() {}

    public static void main(String[] args) {
        String podNamespace = System.getenv("POD_NAMESPACE");
        String context = System.getenv("KUBE_CONTEXT");
        Operator operator = new Operator(o -> {
            if (context != null && !context.isBlank()) {
                o.withKubernetesClient(new KubernetesClientBuilder().withConfig(Config.autoConfigure(context)).build());
                log.info("using kubeconfig context {}", context);
            }
            if (podNamespace != null && !podNamespace.isBlank()) {
                o.withLeaderElectionConfiguration(new LeaderElectionConfiguration("loadtest-operator", podNamespace));
                log.info("leader election on, lease loadtest-operator in namespace {}", podNamespace);
            }
        });
        operator.register(new LoadTestReconciler());
        operator.start();
        log.info("loadtest-operator started - watching LoadTest resources");
    }
}
