/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.debezium.kafka.KafkaCluster;
import io.debezium.kafka.ZookeeperServer;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaTopicList;
import io.strimzi.api.kafka.model.DoneableKafkaTopic;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.KafkaTopicBuilder;
import io.strimzi.test.mockkube.MockKube;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static io.strimzi.test.TestUtils.waitFor;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import io.vertx.core.VertxOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;

@ExtendWith(VertxExtension.class)
public class TopicOperatorMockTest {

    private static final Logger LOGGER = LogManager.getLogger(TopicOperatorMockTest.class);

    private KubernetesClient kubeClient;
    private Session session;
    private KafkaCluster kafkaCluster;
    private Vertx vertx;
    private String deploymentId;
    private AdminClient adminClient;
    private TopicConfigsWatcher topicsConfigWatcher;
    private ZkTopicWatcher topicWatcher;
    private ZkTopicsWatcher topicsWatcher;

    // TODO this is all in common with TOIT, so factor out a common base class

    @AfterEach
    public void tearDown() {
        if (vertx != null && deploymentId != null) {
            vertx.undeploy(deploymentId);
        }
        if (adminClient != null) {
            adminClient.close();
        }
        if (kafkaCluster != null) {
            kafkaCluster.shutdown();
        }
    }

    @BeforeEach
    public void createMockKube(VertxTestContext context) throws Exception {
        assumeTrue(System.getenv("TRAVIS") == null, "This test is flaky on Travis, for unknown reasons");
        VertxOptions options = new VertxOptions().setMetricsOptions(
                new MicrometerMetricsOptions()
                        .setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true))
                        .setEnabled(true));
        vertx = Vertx.vertx(options);
        MockKube mockKube = new MockKube();
        mockKube.withCustomResourceDefinition(Crds.topic(),
                        KafkaTopic.class, KafkaTopicList.class, DoneableKafkaTopic.class);
        kubeClient = mockKube.build();

        kafkaCluster = new KafkaCluster();
        kafkaCluster.addBrokers(1);
        kafkaCluster.deleteDataPriorToStartup(true);
        kafkaCluster.deleteDataUponShutdown(true);
        kafkaCluster.usingDirectory(Files.createTempDirectory("operator-integration-test").toFile());
        kafkaCluster.startup();

        Properties p = new Properties();
        p.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaCluster.brokerList());
        adminClient = AdminClient.create(p);

        Map<String, String> m = new HashMap();
        m.put(io.strimzi.operator.topic.Config.KAFKA_BOOTSTRAP_SERVERS.key, kafkaCluster.brokerList());
        m.put(io.strimzi.operator.topic.Config.ZOOKEEPER_CONNECT.key, "localhost:" + zkPort(kafkaCluster));
        m.put(io.strimzi.operator.topic.Config.ZOOKEEPER_CONNECTION_TIMEOUT_MS.key, "30000");
        m.put(io.strimzi.operator.topic.Config.NAMESPACE.key, "myproject");
        m.put(io.strimzi.operator.topic.Config.FULL_RECONCILIATION_INTERVAL_MS.key, "10000");
        session = new Session(kubeClient, new io.strimzi.operator.topic.Config(m));

        Checkpoint async = context.checkpoint();
        vertx.deployVerticle(session, ar -> {
            if (ar.succeeded()) {
                deploymentId = ar.result();
                topicsConfigWatcher = session.topicConfigsWatcher;
                topicWatcher = session.topicWatcher;
                topicsWatcher = session.topicsWatcher;
                async.flag();
            } else {
                ar.cause().printStackTrace();
                context.failNow(new Throwable("Failed to deploy session"));
            }
        });
        if (!context.awaitCompletion(60, TimeUnit.SECONDS)) {
            context.failNow(new Throwable("Test timeout"));
        }

        int timeout = 30_000;

        waitFor("Topic watcher not started",  1_000, timeout,
            () -> this.topicWatcher.started());
        waitFor("Topic configs watcher not started", 1_000, timeout,
            () -> this.topicsConfigWatcher.started());
        waitFor("Topic watcher not started", 1_000, timeout,
            () -> this.topicsWatcher.started());
        //waitFor(context, () -> this.topicsConfigWatcher.started(), timeout, "Topic configs watcher not started");
        //waitFor(context, () -> this.topicWatcher.started(), timeout, "Topic watcher not started");
    }

    private static int zkPort(KafkaCluster cluster) {
        // TODO Method was added in DBZ-540, so no need for reflection once
        // dependency gets upgraded
        try {
            Field zkServerField = KafkaCluster.class.getDeclaredField("zkServer");
            zkServerField.setAccessible(true);
            return ((ZookeeperServer) zkServerField.get(cluster)).getPort();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void createInKube(KafkaTopic topic) {
        Crds.topicOperation(kubeClient).create(topic);
    }

    private void updateInKube(KafkaTopic topic) {
        LOGGER.info("Updating topic {} in kube", topic.getMetadata().getName());
        Crds.topicOperation(kubeClient).withName(topic.getMetadata().getName()).patch(topic);
    }

    @Test
    public void testCreatedWithoutTopicNameInKube(VertxTestContext context) throws InterruptedException {
        LOGGER.info("Test started");

        int retention = 100_000_000;
        KafkaTopic kt = new KafkaTopicBuilder()
                .withNewMetadata()
                .withName("my-topic")
                .addToLabels("strimzi.io/kind", "topic")
                .addToLabels(io.strimzi.operator.common.model.Labels.KUBERNETES_NAME_LABEL, io.strimzi.operator.common.model.Labels.KUBERNETES_NAME)
                .endMetadata()
                .withNewSpec()
                .withPartitions(1)
                .withReplicas(1)
                .addToConfig("retention.bytes", retention)
                .endSpec().build();

        testCreatedInKube(context, kt);
    }

    void testCreatedInKube(VertxTestContext context, KafkaTopic kt) throws InterruptedException {
        String kubeName = kt.getMetadata().getName();
        String kafkaName = kt.getSpec().getTopicName() != null ? kt.getSpec().getTopicName() : kubeName;
        int retention = (Integer) kt.getSpec().getConfig().get("retention.bytes");

        createInKube(kt);

        // Check created in Kafka
        waitUntilTopicExistsInKafka(kafkaName);
        LOGGER.info("Topic has been created");
        Topic fromKafka = getFromKafka(context, kafkaName);
        context.verify(() -> assertThat(fromKafka.getTopicName().toString(), is(kafkaName)));
        //context.assertEquals(kubeName, fromKafka.getResourceName().toString());
        // Reconcile after no changes
        reconcile(context);
        // Check things still the same
        context.verify(() -> assertThat(fromKafka, is(getFromKafka(context, kafkaName))));

        // Config change + reconcile
        updateInKube(new KafkaTopicBuilder(kt).editSpec().addToConfig("retention.bytes", retention + 1).endSpec().build());
        waitUntilTopicInKafka(kafkaName, config -> Integer.toString(retention + 1).equals(config.get("retention.bytes").value()));
        // Another reconciliation
        reconcile(context);

        // Check things still the same
        context.verify(() -> assertThat(getFromKafka(context, kafkaName), is(new Topic.Builder(fromKafka)
                .withConfigEntry("retention.bytes", Integer.toString(retention + 1))
                .build())));

        // Reconcile after change #partitions change
        // Check things still the same
        // Try to add a matching spec.topicName
        // Check things still the same
        // Try to change spec.topicName
        // Check error
        // Try to change spec.topicName back
        // Check things still the same (recover from error)
        // Try to remove spec.topicName
        // Check things still the same
    }

    Topic getFromKafka(VertxTestContext context, String topicName) throws InterruptedException {
        AtomicReference<Topic> ref = new AtomicReference<>();
        Checkpoint async = context.checkpoint();
        Future<TopicMetadata> kafkaMetadata = session.kafka.topicMetadata(new TopicName(topicName));
        kafkaMetadata.map(metadata -> TopicSerialization.fromTopicMetadata(metadata)).setHandler(fromKafka -> {
            if (fromKafka.succeeded()) {
                ref.set(fromKafka.result());
            } else {
                context.failNow(fromKafka.cause());
            }
            async.flag();
        });
        if (!context.awaitCompletion(60, TimeUnit.SECONDS)) {
            context.failNow(new Throwable("Test timeout"));
        }
        return ref.get();
    }

    private Config waitUntilTopicExistsInKafka(String topicName) {
        return waitUntilTopicInKafka(topicName, desc -> desc != null);
    }

    private Config waitUntilTopicInKafka(String topicName, Predicate<Config> p) {
        ConfigResource configResource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
        AtomicReference<Config> ref = new AtomicReference<>();
        waitFor("Creation of topic " + topicName, 1_000, 60_000, () -> {
            try {
                Map<ConfigResource, Config> descriptionMap = adminClient.describeConfigs(asList(configResource)).all().get();
                Config desc = descriptionMap.get(configResource);
                if (p.test(desc)) {
                    ref.set(desc);
                    return true;
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        });
        return ref.get();
    }

    void reconcile(VertxTestContext context) throws InterruptedException {
        Checkpoint async = context.checkpoint();
        session.topicOperator.reconcileAllTopics("test").setHandler(ar -> {
            if (!ar.succeeded()) {
                context.failNow(ar.cause());
            }
            async.flag();
        });
        if (!context.awaitCompletion(60, TimeUnit.SECONDS)) {
            context.failNow(new Throwable("Test timeout"));
        }
    }


    @Test
    public void testCreatedWithSameTopicNameInKube(VertxTestContext context) throws InterruptedException {

        int retention = 100_000_000;
        KafkaTopic kt = new KafkaTopicBuilder()
                .withNewMetadata()
                .withName("my-topic")
                .addToLabels("strimzi.io/kind", "topic")
                .endMetadata()
                .withNewSpec()
                .withTopicName("my-topic") // the same as metadata.name
                .withPartitions(1)
                .withReplicas(1)
                .addToConfig("retention.bytes", retention)
                .endSpec().build();

        testCreatedInKube(context, kt);
    }

    @Test
    public void testCreatedWithDifferentTopicNameInKube(VertxTestContext context) throws InterruptedException {
        int retention = 100_000_000;
        KafkaTopic kt = new KafkaTopicBuilder()
                .withNewMetadata()
                .withName("my-topic")
                .addToLabels("strimzi.io/kind", "topic")
                .endMetadata()
                .withNewSpec()
                    .withTopicName("DIFFERENT") // different to metadata.name
                    .withPartitions(1)
                    .withReplicas(1)
                    .addToConfig("retention.bytes", retention)
                .endSpec().build();

        testCreatedInKube(context, kt);
    }

}
