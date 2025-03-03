/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.pulsar.v2_8.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.InstrumenterBuilder;
import io.opentelemetry.instrumentation.api.instrumenter.messaging.MessageOperation;
import io.opentelemetry.instrumentation.api.instrumenter.messaging.MessagingAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.messaging.MessagingAttributesGetter;
import io.opentelemetry.instrumentation.api.instrumenter.messaging.MessagingSpanNameExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.net.NetClientAttributesExtractor;
import io.opentelemetry.instrumentation.api.internal.InstrumenterUtil;
import io.opentelemetry.javaagent.bootstrap.internal.ExperimentalConfig;
import io.opentelemetry.javaagent.bootstrap.internal.InstrumentationConfig;
import io.opentelemetry.javaagent.instrumentation.pulsar.v2_8.VirtualFieldStore;
import java.time.Instant;
import java.util.List;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;

public final class PulsarSingletons {
  private static final String INSTRUMENTATION_NAME = "io.opentelemetry.apache-pulsar-2.8";

  private static final OpenTelemetry TELEMETRY = GlobalOpenTelemetry.get();
  private static final TextMapPropagator PROPAGATOR =
      TELEMETRY.getPropagators().getTextMapPropagator();
  private static final List<String> capturedHeaders =
      ExperimentalConfig.get().getMessagingHeaders();

  private static final Instrumenter<PulsarRequest, Void> CONSUMER_PROCESS_INSTRUMENTER =
      createConsumerProcessInstrumenter();
  private static final Instrumenter<PulsarRequest, Void> CONSUMER_RECEIVE_INSTRUMENTER =
      createConsumerReceiveInstrumenter();
  private static final Instrumenter<PulsarRequest, Void> PRODUCER_INSTRUMENTER =
      createProducerInstrumenter();

  public static Instrumenter<PulsarRequest, Void> consumerProcessInstrumenter() {
    return CONSUMER_PROCESS_INSTRUMENTER;
  }

  public static Instrumenter<PulsarRequest, Void> consumerReceiveInstrumenter() {
    return CONSUMER_RECEIVE_INSTRUMENTER;
  }

  public static Instrumenter<PulsarRequest, Void> producerInstrumenter() {
    return PRODUCER_INSTRUMENTER;
  }

  private static Instrumenter<PulsarRequest, Void> createConsumerReceiveInstrumenter() {
    MessagingAttributesGetter<PulsarRequest, Void> getter =
        PulsarMessagingAttributesGetter.INSTANCE;

    return Instrumenter.<PulsarRequest, Void>builder(
            TELEMETRY,
            INSTRUMENTATION_NAME,
            MessagingSpanNameExtractor.create(getter, MessageOperation.RECEIVE))
        .addAttributesExtractor(createMessagingAttributesExtractor(MessageOperation.RECEIVE))
        .addAttributesExtractor(
            NetClientAttributesExtractor.create(new PulsarNetClientAttributesGetter()))
        .buildConsumerInstrumenter(MessageTextMapGetter.INSTANCE);
  }

  private static Instrumenter<PulsarRequest, Void> createConsumerProcessInstrumenter() {
    MessagingAttributesGetter<PulsarRequest, Void> getter =
        PulsarMessagingAttributesGetter.INSTANCE;

    return Instrumenter.<PulsarRequest, Void>builder(
            TELEMETRY,
            INSTRUMENTATION_NAME,
            MessagingSpanNameExtractor.create(getter, MessageOperation.PROCESS))
        .addAttributesExtractor(createMessagingAttributesExtractor(MessageOperation.PROCESS))
        .buildInstrumenter();
  }

  private static Instrumenter<PulsarRequest, Void> createProducerInstrumenter() {
    MessagingAttributesGetter<PulsarRequest, Void> getter =
        PulsarMessagingAttributesGetter.INSTANCE;

    InstrumenterBuilder<PulsarRequest, Void> builder =
        Instrumenter.<PulsarRequest, Void>builder(
                TELEMETRY,
                INSTRUMENTATION_NAME,
                MessagingSpanNameExtractor.create(getter, MessageOperation.SEND))
            .addAttributesExtractor(createMessagingAttributesExtractor(MessageOperation.SEND))
            .addAttributesExtractor(
                NetClientAttributesExtractor.create(new PulsarNetClientAttributesGetter()));

    if (InstrumentationConfig.get()
        .getBoolean("otel.instrumentation.apache-pulsar.experimental-span-attributes", false)) {
      builder.addAttributesExtractor(ExperimentalProducerAttributesExtractor.INSTANCE);
    }

    return builder.buildProducerInstrumenter(MessageTextMapSetter.INSTANCE);
  }

  private static AttributesExtractor<PulsarRequest, Void> createMessagingAttributesExtractor(
      MessageOperation operation) {
    return MessagingAttributesExtractor.builder(PulsarMessagingAttributesGetter.INSTANCE, operation)
        .setCapturedHeaders(capturedHeaders)
        .build();
  }

  public static Context startAndEndConsumerReceive(
      Context parent, Message<?> message, long start, Consumer<?> consumer) {
    if (message == null) {
      return null;
    }
    String brokerUrl = VirtualFieldStore.extract(consumer);
    PulsarRequest request = PulsarRequest.create(message, brokerUrl);
    if (!CONSUMER_RECEIVE_INSTRUMENTER.shouldStart(parent, request)) {
      return null;
    }
    // startAndEnd not supports extract trace context from carrier
    // start not supports custom startTime
    // extract trace context by using TEXT_MAP_PROPAGATOR here.
    return InstrumenterUtil.startAndEnd(
        CONSUMER_RECEIVE_INSTRUMENTER,
        PROPAGATOR.extract(parent, request, MessageTextMapGetter.INSTANCE),
        request,
        null,
        null,
        Instant.ofEpochMilli(start),
        Instant.now());
  }

  private PulsarSingletons() {}
}
