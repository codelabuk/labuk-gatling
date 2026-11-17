package org.codelabuk.perf.operator;

import io.javaoperatorsdk.operator.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts the operator; inside a cluster it uses the pod's service account. */
public final class OperatorMain {

    private static final Logger log = LoggerFactory.getLogger(OperatorMain.class);

    private OperatorMain() {}

    public static void main(String[] args) {
        Operator operator = new Operator();
        operator.register(new LoadTestReconciler());
        operator.start();
        log.info("loadtest-operator started - watching LoadTest resources");
    }
}
