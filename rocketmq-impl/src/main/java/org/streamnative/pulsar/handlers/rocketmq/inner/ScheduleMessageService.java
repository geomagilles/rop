/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.streamnative.pulsar.handlers.rocketmq.inner;

import com.google.common.base.Preconditions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Messages;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionMode;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.TopicFilterType;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.store.MessageExtBrokerInner;
import org.streamnative.pulsar.handlers.rocketmq.RocketMQServiceConfiguration;
import org.streamnative.pulsar.handlers.rocketmq.inner.format.RopEntryFormatter;
import org.streamnative.pulsar.handlers.rocketmq.inner.timer.SystemTimer;
import org.streamnative.pulsar.handlers.rocketmq.utils.CommonUtils;
import org.streamnative.pulsar.handlers.rocketmq.utils.PulsarUtil;
import org.streamnative.pulsar.handlers.rocketmq.utils.RocketMQTopic;

/**
 * Schedule message service.
 */
@Slf4j
public class ScheduleMessageService {

    private static final long FIRST_DELAY_TIME = 1000L;
    private static final long DELAY_FOR_A_WHILE = 200L;
    private static final long DELAY_FOR_A_PERIOD = 10000L;
    private static final int MAX_FETCH_MESSAGE_NUM = 100;
    /*  key is delayed level  value is delay timeMillis */
    private final Map<Integer, Long> delayLevelTable;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final RocketMQServiceConfiguration config;
    private final RocketMQBrokerController rocketBroker;
    private final ServiceThread expirationReaper;
    private final Timer timer = new Timer();
    private final Map<String, Producer<byte[]>> sendBackProducers;
    private final String scheduleTopicPrefix;
    private String[] delayLevelArray;
    private BrokerService pulsarBroker;
    private List<DeliverDelayedMessageTimerTask> deliverDelayedMessageManager;

    public ScheduleMessageService(final RocketMQBrokerController rocketBroker, RocketMQServiceConfiguration config) {
        this.config = config;
        this.rocketBroker = rocketBroker;
        this.scheduleTopicPrefix = config.getRmqScheduleTopic();
        this.delayLevelTable = new HashMap<>(config.getMaxDelayLevelNum());
        this.parseDelayLevel();
        this.sendBackProducers = new ConcurrentHashMap<>();
        this.expirationReaper = new ServiceThread() {
            private static final long advanceTimeInterval = 100L;

            @Override
            public String getServiceName() {
                return "ScheduleMessageService-expirationReaper-thread";
            }

            @Override
            public void run() {
                log.info(getServiceName() + " service started.");
                Preconditions.checkNotNull(deliverDelayedMessageManager);
                while (!this.isStopped()) {
                    deliverDelayedMessageManager.parallelStream().forEach((i) -> i.advanceClock(advanceTimeInterval));
                }
            }
        };
        this.expirationReaper.setDaemon(true);
    }

    public String getDelayedTopicName(int timeDelayedLevel) {
        String delayedTopicName = ScheduleMessageService.this.scheduleTopicPrefix + CommonUtils.UNDERSCORE_CHAR
                + delayLevelArray[timeDelayedLevel - 1];
        RocketMQTopic rocketMQTopic = RocketMQTopic.getRocketMQMetaTopic(delayedTopicName);
        return rocketMQTopic.getPulsarFullName();
    }

    public String getDelayedTopicName(int timeDelayedLevel, int partitionId) {
        String delayedTopicName = ScheduleMessageService.this.scheduleTopicPrefix + CommonUtils.UNDERSCORE_CHAR
                + delayLevelArray[timeDelayedLevel - 1];
        RocketMQTopic rocketMQTopic = RocketMQTopic.getRocketMQMetaTopic(delayedTopicName);
        return rocketMQTopic.getPartitionTopicName(partitionId).toString();
    }

    public String getDelayedTopicConsumerName(int timeDelayedLevel) {
        String delayedTopicName = ScheduleMessageService.this.scheduleTopicPrefix + CommonUtils.UNDERSCORE_CHAR
                + delayLevelArray[timeDelayedLevel - 1];
        return delayedTopicName + CommonUtils.UNDERSCORE_CHAR + "consumer";
    }

    public long computeDeliverTimestamp(final int delayLevel, final long storeTimestamp) {
        Long time = this.delayLevelTable.get(delayLevel);
        if (time != null) {
            return time + storeTimestamp;
        }
        return storeTimestamp + 1000;
    }

    public void start() throws Exception {
        if (started.compareAndSet(false, true)) {
            this.pulsarBroker = rocketBroker.getBrokerService();
            this.createDelayedSubscription();
            this.deliverDelayedMessageManager = delayLevelTable.keySet().stream()
                    .collect(ArrayList::new, (arr, level) -> {
                        arr.add(new DeliverDelayedMessageTimerTask(level));
                    }, ArrayList::addAll);
            this.deliverDelayedMessageManager
                    .forEach((i) -> this.timer.schedule(i, FIRST_DELAY_TIME, DELAY_FOR_A_WHILE));
            this.expirationReaper.start();
        }
    }

    public void shutdown() {
        if (this.started.compareAndSet(true, false)) {
            expirationReaper.shutdown();
            deliverDelayedMessageManager.forEach(DeliverDelayedMessageTimerTask::close);
            sendBackProducers.values().forEach(Producer::closeAsync);
            timer.cancel();
        }
    }

    public boolean isStarted() {
        return started.get();
    }

    public boolean parseDelayLevel() {
        HashMap<String, Long> timeUnitTable = new HashMap<>();
        timeUnitTable.put("s", 1000L);
        timeUnitTable.put("m", 1000L * 60);
        timeUnitTable.put("h", 1000L * 60 * 60);
        timeUnitTable.put("d", 1000L * 60 * 60 * 24);

        String levelString = this.config.getMessageDelayLevel();
        try {
            this.delayLevelArray = levelString.split(" ");
            for (int i = 0; i < delayLevelArray.length; i++) {
                String value = delayLevelArray[i];
                String ch = value.substring(value.length() - 1);
                Long tu = timeUnitTable.get(ch);
                int level = i + 1;
                long num = Long.parseLong(value.substring(0, value.length() - 1));
                long delayTimeMillis = tu * num;
                this.delayLevelTable.put(level, delayTimeMillis);
            }
        } catch (Exception e) {
            log.error("parseDelayLevel exception, evelString String = {}", levelString, e);
            return false;
        }
        return true;
    }

    class DeliverDelayedMessageTimerTask extends TimerTask {

        private static final int PULL_MESSAGE_TIMEOUT_MS = 500;
        private static final int SEND_MESSAGE_TIMEOUT_MS = 3000;
        private final PulsarService pulsarService;
        private final RocketMQBrokerController rocketBroker;
        private final int delayLevel;
        private final RopEntryFormatter formatter = new RopEntryFormatter();
        private final SystemTimer timeoutTimer;
        private Consumer<byte[]> delayedConsumer = null;

        public DeliverDelayedMessageTimerTask(int delayLevel) {
            this.delayLevel = delayLevel;
            this.pulsarService = ScheduleMessageService.this.pulsarBroker.pulsar();
            this.rocketBroker = ScheduleMessageService.this.rocketBroker;
            this.timeoutTimer = SystemTimer.builder().executorName("DeliverDelayedMessageTimeWheelExecutor").build();
        }

        public void close() {
            if (delayedConsumer != null) {
                delayedConsumer.closeAsync();
                timeoutTimer.shutdown();
            }
        }

        public void advanceClock(long timeoutMs) {
            timeoutTimer.advanceClock(timeoutMs);
        }

        @Override
        public void run() {
            try {
                createConsumer();
                AtomicInteger msgNum = new AtomicInteger(0);
                while (msgNum.get() < config.getMaxScheduleMsgBatchSize()
                        && timeoutTimer.size() < config.getMaxScheduleMsgBatchSize()
                        && ScheduleMessageService.this.isStarted()) {
                    CompletableFuture<Messages<byte[]>> messagesFuture = this.delayedConsumer
                            .batchReceiveAsync();
                    Messages<byte[]> messages = messagesFuture.get(PULL_MESSAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (messages.size() == 0) {
                        break;
                    }

                    messages.forEach(message -> {
                        MessageExt messageExt = this.formatter.decodePulsarMessage(message);
                        long deliveryTime = computeDeliverTimestamp(this.delayLevel,
                                messageExt.getStoreTimestamp());
                        long diff = deliveryTime - Instant.now().toEpochMilli();
                        diff = diff < 0 ? 0 : diff;
                        log.debug(
                                "Retry delayedTime: delayLeve=[{}], delayTime=[{}], "
                                        + "bornTime=[{}], storeTime=[{}], deliveryTime=[{}].",
                                new Object[]{delayLevel, delayLevelTable.get(delayLevel),
                                        messageExt.getBornTimestamp(),
                                        messageExt.getStoreTimestamp(), deliveryTime});
                        timeoutTimer
                                .add(new org.streamnative.pulsar.handlers.rocketmq.inner.timer.TimerTask(diff) {
                                    @Override
                                    public void run() {
                                        try {
                                            log.debug("Retry delayedTime: needDelayMs=[{}],real diff =[{}].",
                                                    this.delayMs,
                                                    deliveryTime - Instant.now().toEpochMilli());
                                            MessageExtBrokerInner msgInner = messageTimeup(messageExt);
                                            if (MixAll.RMQ_SYS_TRANS_HALF_TOPIC.equals(messageExt.getTopic())) {
                                                log.error(
                                                        "[BUG] the real topic of schedule msg is {}, "
                                                                + "discard the msg. msg={}",
                                                        messageExt.getTopic(), messageExt);
                                                return;
                                            }

                                            RocketMQTopic rmqTopic = new RocketMQTopic(msgInner.getTopic());
                                            String pTopic = rmqTopic.getPartitionName(msgInner.getQueueId());
                                            Producer<byte[]> producer = getProducerFromCache(pTopic);
                                            CompletableFuture<MessageId> sendFuture = producer.newMessage()
                                                    .value(formatter.encode(msgInner, 1).get(0))
                                                    .sendAsync();
                                            sendFuture.whenCompleteAsync((msgId, ex) -> {
                                                if (ex == null) {
                                                    delayedConsumer.acknowledgeAsync(message);
                                                } else {
                                                    delayedConsumer.negativeAcknowledge(message);
                                                }
                                            });
                                        } catch (Exception ex) {
                                            log.warn("delayedMessageSender send message[{}] failed.",
                                                    message.getMessageId(), ex);
                                            delayedConsumer.negativeAcknowledge(message);
                                        }
                                    }
                                });
                    });
                    msgNum.addAndGet(messages.size());
                }
            } catch (Exception e) {
                log.warn("DeliverDelayedMessageTimerTask[delayLevel={}] pull message exception.", this.delayLevel,
                        e);
                if (!ScheduleMessageService.this.isStarted()) {
                    Thread.interrupted();
                }
            }
        }

        private Producer<byte[]> getProducerFromCache(String pulsarTopic) {
            Producer<byte[]> producer = sendBackProducers.computeIfAbsent(pulsarTopic, key -> {
                log.info("getProducerFromCache [topic={}].", pulsarTopic);
                try {
                    return rocketBroker.getRopBrokerProxy().getPulsarClient().newProducer()
                            .topic(pulsarTopic)
                            .producerName(pulsarTopic + "_delayedMessageSender")
                            .enableBatching(true)
                            .sendTimeout(SEND_MESSAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .create();
                } catch (Exception e) {
                    log.warn("getProducerFromCache[topic={}] error.", pulsarTopic, e);
                }
                return null;
            });
            return producer;
        }

        private void createConsumer() {
            try {
                if (delayedConsumer != null) {
                    return;
                }
                PulsarClientImpl pulsarClient = rocketBroker.getRopBrokerProxy().getPulsarClient();
                log.info("Begin to Create delayed consumer, the client config value: [{}]",
                        pulsarClient.getConfiguration());
                this.delayedConsumer = rocketBroker.getRopBrokerProxy().getPulsarClient()
                        .newConsumer()
                        .receiverQueueSize(MAX_FETCH_MESSAGE_NUM)
                        .subscriptionMode(SubscriptionMode.Durable)
                        .subscriptionType(SubscriptionType.Shared)
                        .subscriptionName(getDelayedTopicConsumerName(delayLevel))
                        .topic(getDelayedTopicName(delayLevel))
                        .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                        .deadLetterPolicy(DeadLetterPolicy.builder()
                                .maxRedeliverCount(delayLevel).build())
                        .subscribe();
                log.info("The client config value: [{}]", pulsarClient.getConfiguration());
            } catch (Exception e) {
                log.error("Create delayed topic[delayLevel={}] consumer error.", delayLevel, e);
                throw new RuntimeException("Create delay consumer error");
            }
        }

        private MessageExtBrokerInner messageTimeup(MessageExt msgExt) {
            MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
            msgInner.setBody(msgExt.getBody());
            msgInner.setFlag(msgExt.getFlag());
            MessageAccessor.setProperties(msgInner, msgExt.getProperties());

            TopicFilterType topicFilterType = MessageExt.parseTopicFilterType(msgInner.getSysFlag());
            long tagsCodeValue =
                    MessageExtBrokerInner.tagsString2tagsCode(topicFilterType, msgInner.getTags());
            msgInner.setTagsCode(tagsCodeValue);
            msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgExt.getProperties()));

            msgInner.setSysFlag(msgExt.getSysFlag());
            msgInner.setBornTimestamp(msgExt.getBornTimestamp());
            msgInner.setBornHost(msgExt.getBornHost());
            msgInner.setStoreHost(msgExt.getStoreHost());
            msgInner.setReconsumeTimes(msgExt.getReconsumeTimes());

            msgInner.setWaitStoreMsgOK(false);
            MessageAccessor.clearProperty(msgInner, MessageConst.PROPERTY_DELAY_TIME_LEVEL);
            msgInner.setTopic(msgInner.getProperty(MessageConst.PROPERTY_REAL_TOPIC));
            String queueIdStr = msgInner.getProperty(MessageConst.PROPERTY_REAL_QUEUE_ID);
            int queueId = Integer.parseInt(queueIdStr);
            msgInner.setQueueId(queueId);
            return msgInner;
        }
    }

    private void createDelayedSubscription() throws PulsarServerException {
        PulsarAdmin adminClient = rocketBroker.getBrokerService().pulsar().getAdminClient();
        Preconditions.checkNotNull(adminClient, "pulsar admin client can't be null.");
        IntStream.range(1, delayLevelArray.length + 1).forEach((delayedLevel) -> {
            log.info("createDelayedSubscription for delayedLevel: {}. ", delayedLevel);
            String consumerName = getDelayedTopicConsumerName(delayedLevel);
            String topicName = getDelayedTopicName(delayedLevel);
            PulsarUtil.createSubscriptionIfNotExist(adminClient, topicName, consumerName, MessageId.earliest);
        });
    }
}
