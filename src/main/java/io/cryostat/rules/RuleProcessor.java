/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.rules;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.script.ScriptException;

import org.openjdk.jmc.flightrecorder.configuration.recording.RecordingOptionsBuilder;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import io.cryostat.configuration.CredentialsManager;
import io.cryostat.configuration.CredentialsManager.CredentialsEvent;
import io.cryostat.core.log.Logger;
import io.cryostat.core.net.Credentials;
import io.cryostat.core.templates.TemplateType;
import io.cryostat.net.ConnectionDescriptor;
import io.cryostat.net.TargetConnectionManager;
import io.cryostat.platform.PlatformClient;
import io.cryostat.platform.ServiceRef;
import io.cryostat.platform.TargetDiscoveryEvent;
import io.cryostat.recordings.RecordingArchiveHelper;
import io.cryostat.recordings.RecordingMetadataManager;
import io.cryostat.recordings.RecordingMetadataManager.Metadata;
import io.cryostat.recordings.RecordingOptionsBuilderFactory;
import io.cryostat.recordings.RecordingTargetHelper;
import io.cryostat.rules.RuleRegistry.RuleEvent;
import io.cryostat.util.events.Event;
import io.cryostat.util.events.EventListener;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import org.apache.commons.lang3.tuple.Pair;

public class RuleProcessor extends AbstractVerticle implements Consumer<TargetDiscoveryEvent> {

    private final PlatformClient platformClient;
    private final RuleRegistry registry;
    private final CredentialsManager credentialsManager;
    private final RecordingOptionsBuilderFactory recordingOptionsBuilderFactory;
    private final TargetConnectionManager targetConnectionManager;
    private final RecordingArchiveHelper recordingArchiveHelper;
    private final RecordingTargetHelper recordingTargetHelper;
    private final RecordingMetadataManager metadataManager;
    private final PeriodicArchiverFactory periodicArchiverFactory;
    private final Logger logger;

    private final Map<Pair<ServiceRef, Rule>, Set<Long>> tasks;

    RuleProcessor(
            Vertx vertx,
            PlatformClient platformClient,
            RuleRegistry registry,
            CredentialsManager credentialsManager,
            RecordingOptionsBuilderFactory recordingOptionsBuilderFactory,
            TargetConnectionManager targetConnectionManager,
            RecordingArchiveHelper recordingArchiveHelper,
            RecordingTargetHelper recordingTargetHelper,
            RecordingMetadataManager metadataManager,
            PeriodicArchiverFactory periodicArchiverFactory,
            Logger logger) {
        this.vertx = vertx;
        this.platformClient = platformClient;
        this.registry = registry;
        this.credentialsManager = credentialsManager;
        this.recordingOptionsBuilderFactory = recordingOptionsBuilderFactory;
        this.targetConnectionManager = targetConnectionManager;
        this.recordingArchiveHelper = recordingArchiveHelper;
        this.recordingTargetHelper = recordingTargetHelper;
        this.metadataManager = metadataManager;
        this.periodicArchiverFactory = periodicArchiverFactory;
        this.logger = logger;
        this.tasks = new HashMap<>();

        this.registry.addListener(this.ruleListener());
        this.credentialsManager.addListener(this.credentialsListener());
    }

    @Override
    public void start() {
        this.platformClient.addTargetDiscoveryListener(this);
    }

    @Override
    public void stop() {
        this.platformClient.removeTargetDiscoveryListener(this);
        this.tasks.forEach((ruleExecution, ids) -> ids.forEach(vertx::cancelTimer));
        this.tasks.clear();
    }

    public EventListener<RuleRegistry.RuleEvent, Rule> ruleListener() {
        return new EventListener<RuleRegistry.RuleEvent, Rule>() {

            @Override
            public void onEvent(Event<RuleEvent, Rule> event) {
                switch (event.getEventType()) {
                    case ADDED:
                        vertx.<List<ServiceRef>>executeBlocking(
                                promise ->
                                        promise.complete(
                                                platformClient.listUniqueReachableServices()),
                                false,
                                result ->
                                        result.result().stream()
                                                .filter(
                                                        serviceRef ->
                                                                event.getPayload().isEnabled()
                                                                        && registry.applies(
                                                                                event.getPayload(),
                                                                                serviceRef))
                                                .forEach(
                                                        serviceRef ->
                                                                activate(
                                                                        event.getPayload(),
                                                                        serviceRef)));
                        break;
                    case REMOVED:
                        deactivate(event.getPayload(), null);
                        break;
                    case UPDATED:
                        if (!event.getPayload().isEnabled()) {
                            deactivate(event.getPayload(), null);
                        } else {
                            vertx.<List<ServiceRef>>executeBlocking(
                                    promise ->
                                            promise.complete(
                                                    platformClient.listUniqueReachableServices()),
                                    false,
                                    result ->
                                            result.result().stream()
                                                    .filter(
                                                            serviceRef ->
                                                                    registry.applies(
                                                                            event.getPayload(),
                                                                            serviceRef))
                                                    .forEach(
                                                            serviceRef ->
                                                                    activate(
                                                                            event.getPayload(),
                                                                            serviceRef)));
                        }
                        break;
                    default:
                        throw new UnsupportedOperationException(event.getEventType().toString());
                }
            }
        };
    }

    public EventListener<CredentialsManager.CredentialsEvent, String> credentialsListener() {
        return new EventListener<CredentialsManager.CredentialsEvent, String>() {

            @Override
            public void onEvent(Event<CredentialsEvent, String> event) {
                switch (event.getEventType()) {
                    case ADDED:
                        credentialsManager
                                .resolveMatchingTargets(event.getPayload())
                                .forEach(
                                        sr -> {
                                            registry.getRules(sr).stream()
                                                    .filter(Rule::isEnabled)
                                                    .forEach(rule -> activate(rule, sr));
                                        });
                        break;
                    case REMOVED:
                        break;
                    default:
                        throw new UnsupportedOperationException(event.getEventType().toString());
                }
            }
        };
    }

    @Override
    public synchronized void accept(TargetDiscoveryEvent tde) {
        switch (tde.getEventKind()) {
            case FOUND:
                if (!platformClient.contains(tde.getServiceRef())) {
                    registry.getRules(tde.getServiceRef())
                            .forEach(
                                    rule -> {
                                        if (rule.isEnabled()) {
                                            activate(rule, tde.getServiceRef());
                                        }
                                    });
                }
                break;
            case LOST:
                deactivate(null, tde.getServiceRef());
                break;
            case MODIFIED:
                break;
            default:
                throw new UnsupportedOperationException(tde.getEventKind().toString());
        }
    }

    private void activate(Rule rule, ServiceRef serviceRef) {
        if (!rule.isEnabled()) {
            this.logger.trace(
                    "Activating rule {} for target {} aborted, rule is disabled {} ",
                    rule.getName(),
                    serviceRef.getServiceUri(),
                    rule.isEnabled());
            return;
        }
        if (tasks.containsKey(Pair.of(serviceRef, rule))) {
            this.logger.trace(
                    "Activating rule {} for target {} aborted, rule is already active",
                    rule.getName(),
                    serviceRef.getServiceUri());
            return;
        }
        this.logger.trace(
                "Activating rule {} for target {}", rule.getName(), serviceRef.getServiceUri());

        vertx.<Credentials>executeBlocking(
                        promise -> {
                            try {
                                Credentials creds = credentialsManager.getCredentials(serviceRef);
                                promise.complete(creds);
                            } catch (ScriptException e) {
                                promise.fail(e);
                            }
                        })
                .onSuccess(c -> logger.trace("Rule activation successful"))
                .onSuccess(
                        credentials -> {
                            if (rule.isArchiver()) {
                                try {
                                    archiveRuleRecording(
                                            new ConnectionDescriptor(serviceRef, credentials),
                                            rule);
                                } catch (Exception e) {
                                    logger.error(e);
                                }
                            } else {
                                try {
                                    startRuleRecording(
                                            new ConnectionDescriptor(serviceRef, credentials),
                                            rule);
                                } catch (Exception e) {
                                    logger.error(e);
                                }

                                PeriodicArchiver periodicArchiver =
                                        periodicArchiverFactory.create(
                                                serviceRef,
                                                credentialsManager,
                                                rule,
                                                recordingArchiveHelper,
                                                this::archivalFailureHandler);
                                Pair<ServiceRef, Rule> key = Pair.of(serviceRef, rule);
                                Set<Long> ids = tasks.computeIfAbsent(key, k -> new HashSet<>());
                                int initialDelay = rule.getInitialDelaySeconds();
                                int archivalPeriodSeconds = rule.getArchivalPeriodSeconds();
                                if (initialDelay <= 0) {
                                    initialDelay = archivalPeriodSeconds;
                                }
                                if (rule.getPreservedArchives() <= 0
                                        || archivalPeriodSeconds <= 0) {
                                    return;
                                }
                                long initialTask =
                                        vertx.setTimer(
                                                Duration.ofSeconds(initialDelay).toMillis(),
                                                initialId -> {
                                                    tasks.get(key).remove(initialId);
                                                    periodicArchiver.run();
                                                    long periodicTask =
                                                            vertx.setPeriodic(
                                                                    Duration.ofSeconds(
                                                                                    archivalPeriodSeconds)
                                                                            .toMillis(),
                                                                    periodicId ->
                                                                            periodicArchiver.run());
                                                    ids.add(periodicTask);
                                                });
                                ids.add(initialTask);
                            }
                        });
    }

    private void deactivate(Rule rule, ServiceRef serviceRef) {
        if (rule == null && serviceRef == null) {
            throw new IllegalArgumentException("Both parameters cannot be null");
        }
        if (rule != null) {
            logger.trace("Deactivating rule {}", rule.getName());
        }
        if (serviceRef != null) {
            logger.trace("Deactivating rules for {}", serviceRef.getServiceUri());
        }
        Iterator<Map.Entry<Pair<ServiceRef, Rule>, Set<Long>>> it = tasks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Pair<ServiceRef, Rule>, Set<Long>> entry = it.next();
            boolean sameRule = Objects.equals(entry.getKey().getRight(), rule);
            boolean sameTarget = Objects.equals(entry.getKey().getLeft(), serviceRef);
            if (sameRule || sameTarget) {
                Set<Long> ids = entry.getValue();
                ids.forEach(
                        (id) -> {
                            vertx.cancelTimer(id);
                            logger.trace("Cancelled timer {}", id);
                        });
                it.remove();
            }
        }
    }

    private Void archivalFailureHandler(Pair<ServiceRef, Rule> key) {
        tasks.get(key).forEach(vertx::cancelTimer);
        tasks.remove(key);
        return null;
    }

    private void archiveRuleRecording(ConnectionDescriptor connectionDescriptor, Rule rule) {
        try {
            targetConnectionManager
                    .executeConnectedTaskAsync(
                            connectionDescriptor,
                            connection -> {
                                IRecordingDescriptor descriptor =
                                        connection.getService().getSnapshotRecording();
                                try {
                                    recordingArchiveHelper
                                            .saveRecording(
                                                    connectionDescriptor, descriptor.getName())
                                            .get();
                                } finally {
                                    connection.getService().close(descriptor);
                                }

                                return null;
                            })
                    .get();
        } catch (Exception e) {
            logger.error(new RuleException(e));
        }
    }

    private void startRuleRecording(ConnectionDescriptor connectionDescriptor, Rule rule) {
        CompletableFuture<IRecordingDescriptor> future =
                targetConnectionManager.executeConnectedTaskAsync(
                        connectionDescriptor,
                        connection -> {
                            RecordingOptionsBuilder builder =
                                    recordingOptionsBuilderFactory
                                            .create(connection.getService())
                                            .name(rule.getRecordingName());
                            if (rule.getMaxAgeSeconds() > 0) {
                                builder = builder.maxAge(rule.getMaxAgeSeconds()).toDisk(true);
                            }
                            if (rule.getMaxSizeBytes() > 0) {
                                builder = builder.maxSize(rule.getMaxSizeBytes()).toDisk(true);
                            }
                            Pair<String, TemplateType> template =
                                    RecordingTargetHelper.parseEventSpecifierToTemplate(
                                            rule.getEventSpecifier());
                            return recordingTargetHelper.startRecording(
                                    true,
                                    connectionDescriptor,
                                    builder.build(),
                                    template.getLeft(),
                                    template.getRight(),
                                    new Metadata(),
                                    false);
                        });
        try {
            future.handleAsync(
                            (recording, throwable) -> {
                                if (throwable != null) {
                                    logger.error(new RuleException(throwable));
                                    return null;
                                }
                                try {
                                    Map<String, String> labels =
                                            new HashMap<>(
                                                    metadataManager
                                                            .getMetadata(
                                                                    connectionDescriptor,
                                                                    recording.getName())
                                                            .getLabels());
                                    labels.put("rule", rule.getName());
                                    metadataManager.setRecordingMetadata(
                                            connectionDescriptor,
                                            recording.getName(),
                                            new Metadata(labels));
                                } catch (IOException ioe) {
                                    logger.error(ioe);
                                }
                                return null;
                            })
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            logger.error(new RuleException(e));
        }
    }
}
