package com.github.lookout.verspaetung

import groovy.transform.TypeChecked

import java.util.concurrent.ConcurrentHashMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import kafka.cluster.Broker
import kafka.client.ClientUtils
import kafka.consumer.SimpleConsumer
import kafka.common.TopicAndPartition
import kafka.javaapi.*
/* UGH */
import scala.collection.JavaConversions

/* Can't type check this because it makes the calls in and out of Scala an
 * atrocious pain in the ass
 */
//@TypeChecked
class KafkaPoller extends Thread {
    private final Integer POLLER_DELAY = (1 * 1000)
    private final String KAFKA_CLIENT_ID = 'VerspaetungClient'
    private final Integer KAFKA_TIMEOUT = (5 * 1000)
    private final Integer KAFKA_BUFFER = (100 * 1024)
    private final Logger logger = LoggerFactory.getLogger(KafkaPoller.class)

    private Boolean keepRunning = true
    private Boolean shouldReconnect = false
    private ConcurrentHashMap<Integer, SimpleConsumer> brokerConsumerMap
    private AbstractMap<TopicPartition, Long> topicOffsetMap
    private List<Broker> brokers
    private List<Closure> onDelta
    private AbstractSet<String> currentTopics

    KafkaPoller(AbstractMap map, AbstractSet topicSet) {
        this.topicOffsetMap = map
        this.currentTopics = topicSet
        this.brokerConsumerMap = [:]
        this.brokers = []
        this.onDelta = []
    }

    void run() {
        logger.info("Starting wait loop")

        while (keepRunning) {
            logger.debug("poll loop")

            if (shouldReconnect) {
                reconnect()
            }

            /* Only makes sense to try to dump meta-data if we've got some
             * topics that we should keep an eye on
             */
            if (this.currentTopics.size() > 0) {
                dumpMetadata()
            }

            Thread.sleep(POLLER_DELAY)
        }
    }

    void dumpMetadata() {
        logger.debug("dumping meta-data")

        def metadata = fetchMetadataForCurrentTopics()

        withTopicsAndPartitions(metadata) { tp, p ->
            try {
                captureLatestOffsetFor(tp, p)
            }
            catch (Exception ex) {
                logger.error("Failed to fetch latest for ${tp.topic}:${tp.partition}", ex)
            }
        }

        logger.debug("finished dumping meta-data")
    }

    /**
     * Invoke the given closure with the TopicPartition and Partition meta-data
     * informationn for all of the topic meta-data that was passed in.
     *
     * The 'metadata' is the expected return from
     * kafka.client.ClientUtils.fetchTopicMetadata
     */
    void withTopicsAndPartitions(Object metadata, Closure closure) {
        withScalaCollection(metadata.topicsMetadata).each { kafka.api.TopicMetadata f ->
            withScalaCollection(f.partitionsMetadata).each { p ->
                TopicPartition tp = new TopicPartition(f.topic, p.partitionId)
                closure.call(tp, p)
            }
        }
    }


    /**
     * Fetch the leader metadata and update our data structures
     */
    void captureLatestOffsetFor(TopicPartition tp, Object partitionMetadata) {
        Integer leaderId = partitionMetadata.leader.get()?.id
        Integer partitionId = partitionMetadata.partitionId

        Long offset = latestFromLeader(leaderId, tp.topic, partitionId)

        this.topicOffsetMap[tp] = offset
    }

    Long latestFromLeader(Integer leaderId, String topic, Integer partition) {
        SimpleConsumer consumer = this.brokerConsumerMap[leaderId]
        TopicAndPartition topicAndPart = new TopicAndPartition(topic, partition)
        /* XXX: A zero clientId into this method might not be right */
        return consumer.earliestOrLatestOffset(topicAndPart, -1, 0)
    }

    Iterable withScalaCollection(scala.collection.Iterable iter) {
        return JavaConversions.asJavaIterable(iter)
    }

    /**
     * Blocking reconnect to the Kafka brokers
     */
    void reconnect() {
        logger.info("Creating SimpleConsumer connections for brokers")
        this.brokers.each { Broker b ->
            SimpleConsumer consumer = new SimpleConsumer(b.host,
                                                         b.port,
                                                         KAFKA_TIMEOUT,
                                                         KAFKA_BUFFER,
                                                         KAFKA_CLIENT_ID)
            consumer.connect()
            this.brokerConsumerMap[b.id] = consumer
        }
        this.shouldReconnect =false
    }

    /**
     * Signal the runloop to safely die after it's next iteration
     */
    void die() {
        this.keepRunning = false
        this.brokerConsumerMap.each { Integer brokerId, SimpleConsumer client ->
            client.disconnect()
        }
    }

    /**
     * Store a new list of KafkaBroker objects and signal a reconnection
     */
    void refresh(List<KafkaBroker> brokers) {
        this.brokers = brokers.collect { KafkaBroker b ->
            new Broker(b.brokerId, b.host, b.port)
        }
        this.shouldReconnect = true
    }

    /**
     * Return the brokers list as an immutable Seq collection for the Kafka
     * scala underpinnings
     */
    private scala.collection.immutable.Seq getBrokersSeq() {
        return JavaConversions.asScalaBuffer(this.brokers).toList()
    }

    /**
     * Return scala.collection.mutable.Set for the given List
     */
    private scala.collection.mutable.Set toScalaSet(Set set) {
        return JavaConversions.asScalaSet(set)
    }


    private Object fetchMetadataForCurrentTopics() {
        return ClientUtils.fetchTopicMetadata(
                            toScalaSet(currentTopics),
                            brokersSeq,
                            KAFKA_CLIENT_ID,
                            KAFKA_TIMEOUT,
                            0)
    }


}
